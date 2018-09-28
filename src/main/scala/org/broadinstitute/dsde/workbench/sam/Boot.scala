package org.broadinstitute.dsde.workbench.sam

import java.io.File
import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{Json, Pem}
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, HttpGoogleDirectoryDAO, HttpGoogleIamDAO, HttpGoogleProjectDAO, HttpGooglePubSubDAO, HttpGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.ExecutionContexts
import org.broadinstitute.dsde.workbench.util.DelegatePool

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

object Boot extends App with LazyLogging {

  private def startup(): Unit = {
    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")
    val googleServicesConfigOption = config.getAs[GoogleServicesConfig]("googleServices")
    val schemaLockConfig = config.as[SchemaLockConfig]("schemaLock")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val dirURI = new URI(directoryConfig.directoryUrl)
    val (socketFactory, defaultPort) = dirURI.getScheme.toLowerCase match {
      case "ldaps" => (SSLContext.getDefault.getSocketFactory, 636)
      case "ldap" => (SocketFactory.getDefault, 389)
      case unsupported => throw new WorkbenchException(s"unsupported directory url scheme: $unsupported")
    }
    val port = if (dirURI.getPort > 0) dirURI.getPort else defaultPort
    val ldapConnectionPool = new LDAPConnectionPool(new LDAPConnection(socketFactory, dirURI.getHost, port, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)

    val directoryDAO = new LdapDirectoryDAO(ldapConnectionPool, directoryConfig)
    val schemaDAO = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap

    def createCloudExt(accessPolicyDAO: AccessPolicyDAO) = googleServicesConfigOption match {
      case Some(googleServicesConfig) =>
        val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")

        val googleDirDaos = (googleServicesConfig.adminSdkServiceAccounts match {
          case None => NonEmptyList.one(Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile), Option(googleServicesConfig.subEmail)))
          case Some(accounts) => accounts.map(account => Json(account.json, Option(googleServicesConfig.subEmail)))
        }).map { credentials =>
          new HttpGoogleDirectoryDAO(googleServicesConfig.appName, credentials, "google")
        }

        val googleDirectoryDAO = DelegatePool[GoogleDirectoryDAO](googleDirDaos)
        val googleIamDAO = new HttpGoogleIamDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google")
        val googlePubSubDAO = new HttpGooglePubSubDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google", googleServicesConfig.groupSyncPubSubProject)
        val googleStorageDAO = new HttpGoogleStorageDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google")
        val googleProjectDAO = new HttpGoogleProjectDAO(googleServicesConfig.appName, Pem(googleServicesConfig.billingPemEmail, new File(googleServicesConfig.pathToBillingPem), Option(googleServicesConfig.billingEmail)), "google")
        val googleKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, googlePubSubDAO, googleServicesConfig, petServiceAccountConfig)
        val notificationDAO = new PubSubNotificationDAO(googlePubSubDAO, googleServicesConfig.notificationTopic)

        new GoogleExtensions(directoryDAO, accessPolicyDAO, googleDirectoryDAO, googlePubSubDAO, googleIamDAO, googleStorageDAO, googleProjectDAO, googleKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, resourceTypeMap(CloudExtensions.resourceTypeName))
      case None => NoExtensions
    }


    def createSamRoutes(cloudExtensions: CloudExtensions, accessPolicyDAO: AccessPolicyDAO): (SamRoutes, UserService, ResourceService, StatusService) = {
      // TODO - https://broadinstitute.atlassian.net/browse/GAWB-3603
      // This should JUST get the value from "emailDomain", but for now we're keeping the backwards compatibility code to
      // fall back to getting the "googleServices.appsDomain"
      val emailDomain = config.as[Option[String]]("emailDomain").getOrElse(config.getString("googleServices.appsDomain"))

      val resourceService = new ResourceService(resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)
      val userService = new UserService(directoryDAO, cloudExtensions)
      val statusService = new StatusService(directoryDAO, cloudExtensions, 10 seconds)
      val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)

      val samRoutes = cloudExtensions match {
        case googleExt: GoogleExtensions => new SamRoutes(resourceService, userService, statusService, managedGroupService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with GoogleExtensionRoutes {
            val googleExtensions = googleExt
            val cloudExtensions = googleExt
          }
        case _ => new SamRoutes(resourceService, userService, statusService, managedGroupService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with NoExtensionRoutes
      }
      (samRoutes, userService, resourceService, statusService)
    }

    for {
      _ <- schemaDAO.init().recoverWith{
        case e: WorkbenchException =>
          logger.error("FATAL - could not update schema to latest version. Is the schema lock stuck? See documentation here for more information: [link]")
          Future.failed(e)
        case t: Throwable =>
          logger.error("FATAL - could not init ldap schema", t)
          Future.failed(t)
      }

      io = ExecutionContexts.blockingThreadPool.use{
        blockingEc =>
          implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)
          val accessPolicyDao = new LdapAccessPolicyDAO(ldapConnectionPool, directoryConfig, blockingEc)
          val cloudExtention = createCloudExt(accessPolicyDao)
          val (sRoutes, userService, resourceService, statusService) = createSamRoutes(cloudExtention, accessPolicyDao)

          for{
            _ <- resourceTypes.toList.map(rt => accessPolicyDao.createResourceType(rt.name)).parSequence.handleErrorWith{
              case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
            }

            _ <- IO.fromFuture(IO(cloudExtention.onBoot(SamApplication(userService, resourceService, statusService))))

            _ <- IO.fromFuture(IO(Http().bindAndHandle(sRoutes.route, "0.0.0.0", 8080))).handleErrorWith{
              case t: Throwable => IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
            }
          } yield ()
      }

      _ <- io.unsafeToFuture()
    } yield ()
  }

  startup()
}
