package org.broadinstitute.dsde.workbench.sam

import java.io.File
import java.net.URI
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO, IOApp, Timer}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk._
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Pem}
import org.broadinstitute.dsde.workbench.google2.util.DistributedLock
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleKmsInterpreter, GoogleKmsService, HttpGoogleDirectoryDAO, HttpGoogleIamDAO, HttpGoogleProjectDAO, HttpGooglePubSubDAO, HttpGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GoogleFirestoreInterpreter, GoogleStorageInterpreter, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config.{AppConfig, GoogleConfig}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.google._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.util.{DelegatePool, ExecutionContexts}
import org.ehcache.Cache
import org.ehcache.config.builders.{CacheConfigurationBuilder, CacheManagerBuilder, ExpiryPolicyBuilder, ResourcePoolsBuilder}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Boot extends IOApp with LazyLogging {
  implicit val ec : ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(64))


  def run(args: List[String]): IO[ExitCode] =
    (startup() *> ExitCode.Success.pure[IO]).recoverWith {
      case NonFatal(t) =>
        logger.error("sam failed to start, trying again in 5s", t)
        IO.sleep(5 seconds) *> run(args)
    }

  private def startup(): IO[Unit] = {

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    val config = ConfigFactory.load()
    val appConfig = AppConfig.readConfig(config)

    val schemaDAO = new JndiSchemaDAO(appConfig.directoryConfig, appConfig.schemaLockConfig)

    val appDependencies = createAppDependencies(appConfig)

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

        binding <- IO.fromFuture(IO(Http().bindAndHandle(dependencies.samRoutes.route, "0.0.0.0", 8080))).onError {
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
      })(ldapConnection => IO(ldapConnection.close()))
  }

  private[sam] def createMemberOfCache(
      cacheName: String,
      maxEntries: Long,
      timeToLive: java.time.Duration): cats.effect.Resource[IO, Cache[WorkbenchSubject, Set[String]]] = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[WorkbenchSubject], classOf[Set[String]], ResourcePoolsBuilder.heap(maxEntries))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(timeToLive))
      )
      .build

    cats.effect.Resource.make {
      IO {
        cacheManager.init()
        cacheManager.getCache(cacheName, classOf[WorkbenchSubject], classOf[Set[String]])
      }
    } { _ =>
      IO(cacheManager.close())
    }
  }

  private[sam] def createResourceCache(
      cacheName: String,
      maxEntries: Long,
      timeToLive: java.time.Duration): cats.effect.Resource[IO, Cache[FullyQualifiedResourceId, Resource]] = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[FullyQualifiedResourceId], classOf[Resource], ResourcePoolsBuilder.heap(maxEntries))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(timeToLive))
      )
      .build

    cats.effect.Resource.make {
      IO {
        cacheManager.init()
        cacheManager.getCache(cacheName, classOf[FullyQualifiedResourceId], classOf[Resource])
      }
    } { _ =>
      IO(cacheManager.close())
    }
  }

  private[sam] def createAppDependencies(
      appConfig: AppConfig)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer): cats.effect.Resource[IO, AppDependencies] =
    for {
      ldapConnectionPool <- createLdapConnectionPool(appConfig.directoryConfig.directoryUrl, appConfig.directoryConfig.user, appConfig.directoryConfig.password, appConfig.directoryConfig.connectionPoolSize, "foreground")
      memberOfCache <- createMemberOfCache("memberof", appConfig.directoryConfig.memberOfCache.maxEntries, appConfig.directoryConfig.memberOfCache.timeToLive)
      resourceCache <- createResourceCache("resource", appConfig.directoryConfig.resourceCache.maxEntries, appConfig.directoryConfig.resourceCache.timeToLive)
      ldapExecutionContext <- ExecutionContexts.fixedThreadPool[IO](appConfig.directoryConfig.connectionPoolSize)
      accessPolicyDao = new LdapAccessPolicyDAO(ldapConnectionPool, appConfig.directoryConfig, ldapExecutionContext, memberOfCache, resourceCache)
      directoryDAO = new LdapDirectoryDAO(ldapConnectionPool, appConfig.directoryConfig, ldapExecutionContext, memberOfCache)

      // This special set of objects are for operations that happen in the background, i.e. not in the immediate service
      // of an api call. They are meant to partition resources so that background processes can't crowd our api calls.
      backgroundLdapConnectionPool <- createLdapConnectionPool(appConfig.directoryConfig.directoryUrl, appConfig.directoryConfig.user, appConfig.directoryConfig.password, appConfig.directoryConfig.backgroundConnectionPoolSize, "background")
      backgroundLdapExecutionContext <- ExecutionContexts.fixedThreadPool[IO](appConfig.directoryConfig.backgroundConnectionPoolSize)
      backgroundAccessPolicyDao = new LdapAccessPolicyDAO(backgroundLdapConnectionPool, appConfig.directoryConfig, backgroundLdapExecutionContext, memberOfCache, resourceCache)(IO.contextShift(backgroundLdapExecutionContext))
      backgroundDirectoryDAO = new LdapDirectoryDAO(backgroundLdapConnectionPool, appConfig.directoryConfig, backgroundLdapExecutionContext, memberOfCache)(backgroundLdapExecutionContext, implicitly[Timer[IO]])

      blockingEc <- ExecutionContexts.fixedThreadPool[IO](24)
      appDependencies <- appConfig.googleConfig match {
        case Some(config) =>
          for {
            googleFire <- GoogleFirestoreInterpreter.firestore[IO](
              config.googleServicesConfig.serviceAccountCredentialJson.firestoreServiceAccountJsonPath.asString)
            googleStorage <- GoogleStorageInterpreter.storage[IO](
              config.googleServicesConfig.serviceAccountCredentialJson.defaultServiceAccountJsonPath.asString)
            googleKmsClient <- GoogleKmsInterpreter.client[IO](
              config.googleServicesConfig.serviceAccountCredentialJson.defaultServiceAccountJsonPath.asString)
          } yield {
            val ioFireStore = GoogleFirestoreInterpreter[IO](googleFire)
            // googleServicesConfig.resourceNamePrefix is an environment specific variable passed in https://github.com/broadinstitute/firecloud-develop/blob/fade9286ff0aec8449121ed201ebc44c8a4d57dd/run-context/fiab/configs/sam/docker-compose.yaml.ctmpl#L24
            // Use resourceNamePrefix to avoid collision between different fiab environments (we share same firestore for fiabs)
            val lock =
              DistributedLock[IO](s"sam-${config.googleServicesConfig.resourceNamePrefix.getOrElse("local")}", appConfig.distributedLockConfig, ioFireStore)
            val newGoogleStorage = GoogleStorageInterpreter[IO](googleStorage, blockingEc)
            val googleKmsInterpreter = GoogleKmsInterpreter[IO](googleKmsClient, blockingEc)
            val resourceTypeMap = appConfig.resourceTypes.map(rt => rt.name -> rt).toMap
            val cloudExtension = createGoogleCloudExt(accessPolicyDao, directoryDAO, config, resourceTypeMap, lock, newGoogleStorage, googleKmsInterpreter)
            val googleGroupSynchronizer = new GoogleGroupSynchronizer(backgroundDirectoryDAO, backgroundAccessPolicyDao, cloudExtension.googleDirectoryDAO, cloudExtension, resourceTypeMap)(backgroundLdapExecutionContext)
            val cloudExtensionsInitializer = new GoogleExtensionsInitializer(cloudExtension, googleGroupSynchronizer)
            createAppDepenciesWithSamRoutes(appConfig, cloudExtensionsInitializer, accessPolicyDao, directoryDAO, backgroundDirectoryDAO)
          }
        case None => cats.effect.Resource.pure[IO, AppDependencies](createAppDepenciesWithSamRoutes(appConfig, NoExtensionsInitializer, accessPolicyDao, directoryDAO, backgroundDirectoryDAO))
      }
    } yield appDependencies

  private[sam] def createGoogleCloudExt(
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      config: GoogleConfig,
      resourceTypeMap: Map[ResourceTypeName, ResourceType],
      distributedLock: DistributedLock[IO],
      googleStorageNew: GoogleStorageService[IO],
      googleKms: GoogleKmsService[IO])(implicit actorSystem: ActorSystem): GoogleExtensions = {
    val googleDirDaos = (config.googleServicesConfig.adminSdkServiceAccounts match {
      case None =>
        NonEmptyList.one(
          Pem(
            WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId),
            new File(config.googleServicesConfig.pemFile),
            Option(config.googleServicesConfig.subEmail)
          ))
      case Some(accounts) => accounts.map(account => Json(account.json, Option(config.googleServicesConfig.subEmail)))
    }).map { credentials =>
      new HttpGoogleDirectoryDAO(config.googleServicesConfig.appName, credentials, "google")
    }

    val googleDirectoryDAO = DelegatePool[GoogleDirectoryDAO](googleDirDaos)
    val googleIamDAO = new HttpGoogleIamDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google"
    )
    val googlePubSubDAO = new HttpGooglePubSubDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google",
      config.googleServicesConfig.groupSyncPubSubProject
    )
    val googleStorageDAO = new HttpGoogleStorageDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google"
    )
    val googleProjectDAO = new HttpGoogleProjectDAO(
      config.googleServicesConfig.appName,
      Pem(WorkbenchEmail(config.googleServicesConfig.serviceAccountClientId), new File(config.googleServicesConfig.pemFile)),
      "google"
    )
    val googleKeyCache =
      new GoogleKeyCache(
        distributedLock,
        googleIamDAO,
        googleStorageDAO,
        googleStorageNew,
        googlePubSubDAO,
        config.googleServicesConfig,
        config.petServiceAccountConfig)
    val notificationDAO = new PubSubNotificationDAO(googlePubSubDAO, config.googleServicesConfig.notificationTopic)

    new GoogleExtensions(
      distributedLock,
      directoryDAO,
      accessPolicyDAO,
      googleDirectoryDAO,
      googlePubSubDAO,
      googleIamDAO,
      googleStorageDAO,
      googleProjectDAO,
      googleKeyCache,
      notificationDAO,
      googleKms,
      config.googleServicesConfig,
      config.petServiceAccountConfig,
      resourceTypeMap
    )
  }

  private[sam] def createAppDepenciesWithSamRoutes(
      config: AppConfig,
      cloudExtensionsInitializer: CloudExtensionsInitializer,
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      backgroundDirectoryDAO: DirectoryDAO)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer): AppDependencies = {
    val resourceTypeMap = config.resourceTypes.map(rt => rt.name -> rt).toMap
    val policyEvaluatorService = PolicyEvaluatorService(config.emailDomain, resourceTypeMap, accessPolicyDAO)
    val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensionsInitializer.cloudExtensions, config.emailDomain)
    val userService = new UserService(directoryDAO, cloudExtensionsInitializer.cloudExtensions)
    val statusService = new StatusService(backgroundDirectoryDAO, cloudExtensionsInitializer.cloudExtensions, 10 seconds)
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensionsInitializer.cloudExtensions, config.emailDomain)
    val samApplication = SamApplication(userService, resourceService, statusService)

    cloudExtensionsInitializer match {
      case GoogleExtensionsInitializer(googleExt, synchronizer) =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with GoogleExtensionRoutes {
          val googleExtensions = googleExt
          val cloudExtensions = googleExt
          val googleGroupSynchronizer = synchronizer
        }
        AppDependencies(routes, samApplication, cloudExtensionsInitializer, directoryDAO, accessPolicyDAO, policyEvaluatorService)
      case _ =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, config.swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with NoExtensionRoutes
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
