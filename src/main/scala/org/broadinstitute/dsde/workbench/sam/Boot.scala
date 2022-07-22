package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import cats.data.NonEmptyList
import cats.effect
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Pem}
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleKmsInterpreter, GoogleKmsService, HttpGoogleDirectoryDAO, HttpGoogleIamDAO, HttpGoogleProjectDAO, HttpGooglePubSubDAO, HttpGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GoogleFirestoreInterpreter, GoogleStorageInterpreter, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException}
import org.broadinstitute.dsde.workbench.oauth2.{ClientId, ClientSecret, OpenIDConnectConfiguration}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardSamUserDirectives}
import org.broadinstitute.dsde.workbench.sam.azure.{AzureService, CrlService}
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.AdminConfig
import org.broadinstitute.dsde.workbench.sam.config.{AppConfig, GoogleConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess._
import org.broadinstitute.dsde.workbench.sam.db.DatabaseNames.DatabaseName
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, DbReference}
import org.broadinstitute.dsde.workbench.sam.google._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.util.DelegatePool
import org.broadinstitute.dsde.workbench.util2.ExecutionContexts
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object Boot extends IOApp with LazyLogging {
  def run(args: List[String]): IO[ExitCode] =
    (startup() *> ExitCode.Success.pure[IO]).recoverWith {
      case NonFatal(t) =>
        logger.error("sam failed to start, trying again in 5s", t)
        IO.sleep(5 seconds) *> run(args)
    }

  private def startup(): IO[Unit] = {

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")

    val config = ConfigFactory.load()
    val appConfig = AppConfig.readConfig(config)

    val schemaDAO = new JndiSchemaDAO(appConfig.directoryConfig, appConfig.schemaLockConfig)

    val appDependencies = createAppDependencies(appConfig)

    val tosCheckEnabled = appConfig.termsOfServiceConfig.enabled

    appDependencies.use { dependencies => // this is where the resource is used
      for {
        _ <- IO.fromFuture(IO(schemaDAO.init())).onError {
          case e: WorkbenchException =>
            IO(logger.error("FATAL - could not update schema to latest version. Is the schema lock stuck?")) *> IO
              .raiseError(e)
          case t: Throwable =>
            IO(logger.error("FATAL - could not init ldap schema", t)) *> IO.raiseError(t)
        }

        _ <- dependencies.samApplication.resourceService.initResourceTypes().onError {
          case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }

        _ <- dependencies.policyEvaluatorService.initPolicy()

        _ <- dependencies.cloudExtensionsInitializer.onBoot(dependencies.samApplication)

        binding <- IO.fromFuture(IO(Http().newServerAt("0.0.0.0", 8080).bind(dependencies.samRoutes.route))).onError {
          case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }
        _ <- IO.fromFuture(IO(binding.whenTerminated))
        _ <- IO(system.terminate())
      } yield ()
    }
  }

  private[sam] def createLdapConnectionPool(directoryUrl: String, user: String, password: String, connectionPoolSize: Int, name: String): cats.effect.Resource[IO, LDAPConnectionPool] = {
    val dirURI = new URI(directoryUrl)
    val (socketFactory, defaultPort) = dirURI.getScheme.toLowerCase match {
      case "ldaps" => (SSLContext.getDefault.getSocketFactory, 636)
      case "ldap" => (SocketFactory.getDefault, 389)
      case unsupported => throw new WorkbenchException(s"unsupported directory url scheme: $unsupported")
    }
    val port = if (dirURI.getPort > 0) dirURI.getPort else defaultPort
    cats.effect.Resource.make(
      IO {
        val connectionPool = new LDAPConnectionPool(
          new LDAPConnection(socketFactory, dirURI.getHost, port, user, password),
          connectionPoolSize, connectionPoolSize
        )
        connectionPool.setCreateIfNecessary(false)
        connectionPool.setMaxWaitTimeMillis(30000)
        connectionPool.setConnectionPoolName(name)
        connectionPool
      })(ldapConnectionPool => IO(ldapConnectionPool.close()))
  }

  private[sam] def createAppDependencies(
      appConfig: AppConfig)(implicit actorSystem: ActorSystem): cats.effect.Resource[IO, AppDependencies] =
    for {
      (foregroundDirectoryDAO, foregroundAccessPolicyDAO, _, registrationDAO, postgresDistributedLockDAO) <- createDAOs(appConfig, DatabaseNames.Write, DatabaseNames.Read, appConfig.directoryConfig.connectionPoolSize, "foreground")

      // This special set of objects are for operations that happen in the background, i.e. not in the immediate service
      // of an api call (foreground). They are meant to partition resources so that background processes can't crowd our api calls.
      (backgroundDirectoryDAO, backgroundAccessPolicyDAO, backgroundLdapExecutionContext, _, _) <- createDAOs(appConfig, DatabaseNames.Background, DatabaseNames.Background, appConfig.directoryConfig.backgroundConnectionPoolSize, "background")

      blockingEc <- ExecutionContexts.fixedThreadPool[IO](24)

      cloudExtensionsInitializer <- cloudExtensionsInitializerResource(
        appConfig,
        foregroundDirectoryDAO,
        foregroundAccessPolicyDAO,
        registrationDAO,
        backgroundDirectoryDAO,
        backgroundAccessPolicyDAO,
        postgresDistributedLockDAO,
        backgroundLdapExecutionContext,
        blockingEc)

      oauth2Config <- cats.effect.Resource.eval(
        OpenIDConnectConfiguration[IO](
          appConfig.oidcConfig.authorityEndpoint,
          ClientId(appConfig.oidcConfig.clientId),
          oidcClientSecret = appConfig.oidcConfig.clientSecret.map(ClientSecret),
          extraGoogleClientId = appConfig.oidcConfig.legacyGoogleClientId.map(ClientId),
          extraAuthParams = Some("prompt=login"))
      )
    } yield createAppDependenciesWithSamRoutes(appConfig, cloudExtensionsInitializer, foregroundAccessPolicyDAO, foregroundDirectoryDAO, registrationDAO, oauth2Config)

  private def cloudExtensionsInitializerResource(
      appConfig: AppConfig,
      foregroundDirectoryDAO: DirectoryDAO,
      foregroundAccessPolicyDAO: AccessPolicyDAO,
      registrationDAO: RegistrationDAO,
      backgroundDirectoryDAO: DirectoryDAO,
      backgroundAccessPolicyDAO: AccessPolicyDAO,
      postgresDistributedLockDAO: PostgresDistributedLockDAO[IO],
      backgroundLdapExecutionContext: ExecutionContext,
      blockingEc: ExecutionContext)(implicit actorSystem: ActorSystem): effect.Resource[IO, CloudExtensionsInitializer] =
    appConfig.googleConfig match {
      case Some(config) =>
        for {
          googleFire <- GoogleFirestoreInterpreter.firestore[IO](
            config.googleServicesConfig.serviceAccountCredentialJson.firestoreServiceAccountJsonPath.asString)
          googleStorage <- GoogleStorageInterpreter.storage[IO](
            config.googleServicesConfig.serviceAccountCredentialJson.defaultServiceAccountJsonPath.asString
          )
          googleKmsClient <- GoogleKmsInterpreter.client[IO](config.googleServicesConfig.serviceAccountCredentialJson.defaultServiceAccountJsonPath.asString)
        } yield {
          implicit val loggerIO: StructuredLogger[IO] = Slf4jLogger.getLogger[IO]

          val ioFireStore = GoogleFirestoreInterpreter[IO](googleFire)
          // googleServicesConfig.resourceNamePrefix is an environment specific variable passed in https://github.com/broadinstitute/firecloud-develop/blob/fade9286ff0aec8449121ed201ebc44c8a4d57dd/run-context/fiab/configs/sam/docker-compose.yaml.ctmpl#L24
          // Use resourceNamePrefix to avoid collision between different fiab environments (we share same firestore for fiabs)
          val newGoogleStorage = GoogleStorageInterpreter[IO](googleStorage, blockerBound = None)
          val googleKmsInterpreter = GoogleKmsInterpreter[IO](googleKmsClient)
          val resourceTypeMap = appConfig.resourceTypes.map(rt => rt.name -> rt).toMap
          val cloudExtension = createGoogleCloudExt(
            foregroundAccessPolicyDAO,
            foregroundDirectoryDAO,
            registrationDAO,
            config,
            resourceTypeMap,
            postgresDistributedLockDAO,
            newGoogleStorage,
            googleKmsInterpreter,
            appConfig.adminConfig
          )
          val googleGroupSynchronizer =
            new GoogleGroupSynchronizer(backgroundDirectoryDAO, backgroundAccessPolicyDAO, cloudExtension.googleDirectoryDAO, cloudExtension, resourceTypeMap)(
              backgroundLdapExecutionContext)
          new GoogleExtensionsInitializer(cloudExtension, googleGroupSynchronizer)
        }
      case None => cats.effect.Resource.pure[IO, CloudExtensionsInitializer](NoExtensionsInitializer)
    }

  private def createDAOs(appConfig: AppConfig,
                         writeDbName: DatabaseName,
                         readDbName: DatabaseName,
                         ldapConnectionPoolSize: Int,
                         ldapConnectionPoolName: String): cats.effect.Resource[IO, (DirectoryDAO, AccessPolicyDAO, ExecutionContext, RegistrationDAO, PostgresDistributedLockDAO[IO])] = {
    for {
      writeDbRef <- DbReference.resource(appConfig.liquibaseConfig, writeDbName)
      readDbRef <- DbReference.resource(appConfig.liquibaseConfig, readDbName)
      ldapConnectionPool <- createLdapConnectionPool(appConfig.directoryConfig.directoryUrl, appConfig.directoryConfig.user, appConfig.directoryConfig.password, ldapConnectionPoolSize, ldapConnectionPoolName)
      ldapExecutionContext <- ExecutionContexts.fixedThreadPool[IO](appConfig.directoryConfig.connectionPoolSize)

      directoryDAO = new PostgresDirectoryDAO(writeDbRef, readDbRef)
      accessPolicyDAO = new PostgresAccessPolicyDAO(writeDbRef, readDbRef)
      registrationDAO = createRegistrationDAO(appConfig, ldapExecutionContext, ldapConnectionPool)
      postgresDistributedLockDAO = new PostgresDistributedLockDAO[IO](writeDbRef, readDbRef, appConfig.distributedLockConfig)
    } yield (directoryDAO, accessPolicyDAO, ldapExecutionContext, registrationDAO, postgresDistributedLockDAO)
  }

  private def createRegistrationDAO(appConfig: AppConfig,
                                    ldapExecutionContext: ExecutionContext,
                                    ldapConnectionPool: LDAPConnectionPool): RegistrationDAO = {
    new LdapRegistrationDAO(ldapConnectionPool, appConfig.directoryConfig, ldapExecutionContext)
  }

  private[sam] def createGoogleCloudExt(
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      registrationDAO: RegistrationDAO,
      config: GoogleConfig,
      resourceTypeMap: Map[ResourceTypeName, ResourceType],
      distributedLock: PostgresDistributedLockDAO[IO],
      googleStorageNew: GoogleStorageService[IO],
      googleKms: GoogleKmsService[IO],
      adminConfig: AdminConfig)(implicit actorSystem: ActorSystem): GoogleExtensions = {
    val workspaceMetricBaseName = "google"
    val googleDirDaos = createGoogleDirDaos(config, workspaceMetricBaseName)
    val googleDirectoryDAO = DelegatePool[GoogleDirectoryDAO](googleDirDaos)
    val googleIamDAO = new HttpGoogleIamDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName
    )
    val notificationPubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName,
      config.googleServicesConfig.notificationPubSubProject
    )
    val googleGroupSyncPubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName,
      config.googleServicesConfig.groupSyncPubSubConfig.project
    )
    val googleDisableUsersPubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName,
      config.googleServicesConfig.disableUsersPubSubConfig.project
    )
    val googleKeyCachePubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName,
      config.googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.project
    )
    val googleStorageDAO = new HttpGoogleStorageDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName
    )
    val googleProjectDAO = new HttpGoogleProjectDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      workspaceMetricBaseName
    )
    val googleKeyCache =
      new GoogleKeyCache(
        distributedLock,
        googleIamDAO,
        googleStorageDAO,
        googleStorageNew,
        googleKeyCachePubSubDAO,
        config.googleServicesConfig,
        config.petServiceAccountConfig)
    val notificationDAO = new PubSubNotificationDAO(notificationPubSubDAO, config.googleServicesConfig.notificationTopic)

    new GoogleExtensions(
      distributedLock,
      directoryDAO,
      registrationDAO,
      accessPolicyDAO,
      googleDirectoryDAO,
      notificationPubSubDAO,
      googleGroupSyncPubSubDAO,
      googleDisableUsersPubSubDAO,
      googleIamDAO,
      googleStorageDAO,
      googleProjectDAO,
      googleKeyCache,
      notificationDAO,
      googleKms,
      config.googleServicesConfig,
      config.petServiceAccountConfig,
      resourceTypeMap,
      adminConfig.superAdminsGroup
    )
  }

  private def createGoogleDirDaos(config: GoogleConfig, workspaceMetricBaseName: String)
                                 (implicit actorSystem: ActorSystem): NonEmptyList[HttpGoogleDirectoryDAO] = {
    val serviceAccountJsons = config.googleServicesConfig.adminSdkServiceAccountPaths.map(
      _.map(path => Files.readAllLines(Paths.get(path)).asScala.mkString))

    val googleCredentials = serviceAccountJsons match {
      case None =>
        NonEmptyList.one(
          Pem(
            WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId),
            new File(config.googleServicesConfig.pemFile),
            Option(config.googleServicesConfig.subEmail)
          ))
      case Some(accounts) => accounts.map(account => Json(account, Option(config.googleServicesConfig.subEmail)))
    }

    googleCredentials.map(credentials =>
      new HttpGoogleDirectoryDAO(config.googleServicesConfig.appName, credentials, workspaceMetricBaseName))
  }

  private[sam] def createAppDependenciesWithSamRoutes(
      config: AppConfig,
      cloudExtensionsInitializer: CloudExtensionsInitializer,
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      registrationDAO: RegistrationDAO,
      oauth2Config: OpenIDConnectConfiguration)(implicit actorSystem: ActorSystem): AppDependencies = {
    val resourceTypeMap = config.resourceTypes.map(rt => rt.name -> rt).toMap
    val policyEvaluatorService = PolicyEvaluatorService(config.emailDomain, resourceTypeMap, accessPolicyDAO, directoryDAO)
    val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensionsInitializer.cloudExtensions, config.emailDomain, config.adminConfig.allowedEmailDomains)
    val tosService = new TosService(directoryDAO, registrationDAO, config.googleConfig.get.googleServicesConfig.appsDomain, config.termsOfServiceConfig)
    val userService = new UserService(directoryDAO, cloudExtensionsInitializer.cloudExtensions, registrationDAO, config.blockedEmailDomains, tosService)
    val statusService = new StatusService(directoryDAO, registrationDAO, cloudExtensionsInitializer.cloudExtensions, DbReference(DatabaseNames.Read, implicitly), 10 seconds)
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensionsInitializer.cloudExtensions, config.emailDomain)
    val samApplication = SamApplication(userService, resourceService, statusService, tosService)
    val azureService = config.azureServicesConfig.map { config =>
      new AzureService(new CrlService(config), directoryDAO)
    }
    cloudExtensionsInitializer match {
      case GoogleExtensionsInitializer(googleExt, synchronizer) =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.termsOfServiceConfig, directoryDAO, registrationDAO, policyEvaluatorService, tosService, config.liquibaseConfig, oauth2Config, azureService)
        with StandardSamUserDirectives with GoogleExtensionRoutes {
          val googleExtensions = googleExt
          val cloudExtensions = googleExt
          val googleGroupSynchronizer = synchronizer
        }
        AppDependencies(routes, samApplication, cloudExtensionsInitializer, directoryDAO, accessPolicyDAO, policyEvaluatorService)
      case _ =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.termsOfServiceConfig, directoryDAO, registrationDAO, policyEvaluatorService, tosService, config.liquibaseConfig, oauth2Config, azureService)
        with StandardSamUserDirectives with NoExtensionRoutes
        AppDependencies(routes, samApplication, NoExtensionsInitializer, directoryDAO, accessPolicyDAO, policyEvaluatorService)
    }
  }
}

final case class AppDependencies(
    samRoutes: SamRoutes,
    samApplication: SamApplication,
    cloudExtensionsInitializer: CloudExtensionsInitializer,
    directoryDAO: DirectoryDAO,
    accessPolicyDAO: AccessPolicyDAO,
    policyEvaluatorService: PolicyEvaluatorService)
