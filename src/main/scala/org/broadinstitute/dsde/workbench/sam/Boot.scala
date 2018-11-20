package org.broadinstitute.dsde.workbench.sam

import java.io.{File, FileInputStream}
import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Pem}
import org.broadinstitute.dsde.workbench.google.{
  GoogleDirectoryDAO,
  HttpGoogleDirectoryDAO,
  HttpGoogleIamDAO,
  HttpGoogleProjectDAO,
  HttpGooglePubSubDAO,
  HttpGoogleStorageDAO
}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config.{AppConfig, DirectoryConfig, GoogleConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.ExecutionContexts
import org.broadinstitute.dsde.workbench.util.DelegatePool

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Boot extends IOApp with LazyLogging {

  def run(args: List[String]): IO[ExitCode] =
    startup() *> ExitCode.Success.pure[IO]

  private def startup(): IO[Unit] = {

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()

    val config = ConfigFactory.load()
    val appConfig = AppConfig.readConfig(config)

    val schemaDAO = new JndiSchemaDAO(appConfig.directoryConfig, appConfig.schemaLockConfig)

    val resourceTypeMap = appConfig.resourceTypes.map(rt => rt.name -> rt).toMap

    val appDependencies = createAppDependencies(appConfig, resourceTypeMap)

    appDependencies.use { dependencies =>
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

        _ <- dependencies.cloudExtensions.onBoot(dependencies.samApplication)

        binding <- IO.fromFuture(IO(Http().bindAndHandle(dependencies.samRoutes.route, "0.0.0.0", 8080))).onError {
          case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }
        _ <- IO.fromFuture(IO(binding.whenTerminated))
        _ <- IO(system.terminate())
      } yield ()
    }
  }

  private[sam] def createAppDependencies(appConfig: AppConfig, resourceTypeMap: Map[ResourceTypeName, ResourceType])(
      implicit actorSystem: ActorSystem,
      materializer: ActorMaterializer): cats.effect.Resource[IO, AppDependencies] =
    for {
      ldapConnectionPool <- createLdapConnectionPool(appConfig.directoryConfig)
      blockingEc <- ExecutionContexts.blockingThreadPool
      accessPolicyDao = new LdapAccessPolicyDAO(ldapConnectionPool, appConfig.directoryConfig, blockingEc)
      directoryDAO = new LdapDirectoryDAO(ldapConnectionPool, appConfig.directoryConfig, blockingEc)
    } yield {
      val cloudExt: CloudExtensions = appConfig.googleConfig match {
        case Some(config) => createGoogleCloudExt(accessPolicyDao, directoryDAO, config, resourceTypeMap)
        case None => NoExtensions
      }
      createAppDependencies(
        appConfig.swaggerConfig,
        appConfig.emailDomain,
        resourceTypeMap,
        cloudExt,
        accessPolicyDao,
        directoryDAO
      )
    }

  private[sam] def readFile(path: String): cats.effect.Resource[IO, FileInputStream] =
    cats.effect.Resource.make(IO(new FileInputStream(path)))(inputStream => IO(inputStream.close()))

  private[sam] def createLdapConnectionPool(directoryConfig: DirectoryConfig): cats.effect.Resource[IO, LDAPConnectionPool] = {
    val dirURI = new URI(directoryConfig.directoryUrl)
    val (socketFactory, defaultPort) = dirURI.getScheme.toLowerCase match {
      case "ldaps" => (SSLContext.getDefault.getSocketFactory, 636)
      case "ldap" => (SocketFactory.getDefault, 389)
      case unsupported => throw new WorkbenchException(s"unsupported directory url scheme: $unsupported")
    }
    val port = if (dirURI.getPort > 0) dirURI.getPort else defaultPort
    cats.effect.Resource.make(
      IO(
        new LDAPConnectionPool(
          new LDAPConnection(socketFactory, dirURI.getHost, port, directoryConfig.user, directoryConfig.password),
          directoryConfig.connectionPoolSize
        )))(ldapConnection => IO(ldapConnection.close()))
  }

  private[sam] def createGoogleCloudExt(
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO,
      config: GoogleConfig,
      resourceTypeMap: Map[ResourceTypeName, ResourceType])(implicit actorSystem: ActorSystem): GoogleExtensions = {
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
      Pem(
        config.googleServicesConfig.billingPemEmail,
        new File(config.googleServicesConfig.pathToBillingPem),
        Option(config.googleServicesConfig.billingEmail)),
      "google"
    )
    val googleKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, googlePubSubDAO, config.googleServicesConfig, config.petServiceAccountConfig)
    val notificationDAO = new PubSubNotificationDAO(googlePubSubDAO, config.googleServicesConfig.notificationTopic)

    new GoogleExtensions(
      directoryDAO,
      accessPolicyDAO,
      googleDirectoryDAO,
      googlePubSubDAO,
      googleIamDAO,
      googleStorageDAO,
      googleProjectDAO,
      googleKeyCache,
      notificationDAO,
      config.googleServicesConfig,
      config.petServiceAccountConfig,
      resourceTypeMap
    )
  }

  private[sam] def createAppDependencies(
      swaggerConfig: SwaggerConfig,
      emailDomain: String,
      resourceTypeMap: Map[ResourceTypeName, ResourceType],
      cloudExtensions: CloudExtensions,
      accessPolicyDAO: AccessPolicyDAO,
      directoryDAO: DirectoryDAO)(implicit actorSystem: ActorSystem, actorMaterializer: ActorMaterializer): AppDependencies = {
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypeMap, accessPolicyDAO)
    val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)
    val userService = new UserService(directoryDAO, cloudExtensions)
    val statusService = new StatusService(directoryDAO, cloudExtensions, 10 seconds)
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)
    val samApplication = SamApplication(userService, resourceService, statusService)

    cloudExtensions match {
      case googleExt: GoogleExtensions =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with GoogleExtensionRoutes {
          val googleExtensions = googleExt
          val cloudExtensions = googleExt
        }
        AppDependencies(routes, samApplication, googleExt, directoryDAO, accessPolicyDAO, policyEvaluatorService)
      case _ =>
        val routes = new SamRoutes(resourceService, userService, statusService, managedGroupService, swaggerConfig, directoryDAO, policyEvaluatorService)
        with StandardUserInfoDirectives with NoExtensionRoutes
        AppDependencies(routes, samApplication, NoExtensions, directoryDAO, accessPolicyDAO, policyEvaluatorService)
    }
  }
}

final case class AppDependencies(
    samRoutes: SamRoutes,
    samApplication: SamApplication,
    cloudExtensions: CloudExtensions,
    directoryDAO: DirectoryDAO,
    accessPolicyDAO: AccessPolicyDAO,
    policyEvaluatorService: PolicyEvaluatorService)
