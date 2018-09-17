package org.broadinstitute.dsde.workbench.sam

import java.io.File
import java.net.URI

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import javax.net.SocketFactory
import javax.net.ssl.SSLContext

import cats.data.NonEmptyList
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
import org.broadinstitute.dsde.workbench.util.DelegatePool

import scala.concurrent.Future
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

    val accessPolicyDAO = new LdapAccessPolicyDAO(ldapConnectionPool, directoryConfig)
    val directoryDAO = new LdapDirectoryDAO(ldapConnectionPool, directoryConfig)
    val schemaDAO = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap

    val cloudExt = googleServicesConfigOption match {
      case Some(googleServicesConfig) =>
        val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")

        val googleDirDaos = (googleServicesConfig.adminSdkServiceAccounts match {
          case None => NonEmptyList.one(Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile), Option(googleServicesConfig.subEmail)))
          case Some(accounts) => accounts.map(account => Json(account.json))
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

    // TODO - https://broadinstitute.atlassian.net/browse/GAWB-3603
    // This should JUST get the value from "emailDomain", but for now we're keeping the backwards compatibility code to
    // fall back to getting the "googleServices.appsDomain"
    val emailDomain = config.as[Option[String]]("emailDomain").getOrElse(config.getString("googleServices.appsDomain"))

    val resourceService = new ResourceService(resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExt, emailDomain)
    val userService = new UserService(directoryDAO, cloudExt)
    val statusService = new StatusService(directoryDAO, cloudExt, 10 seconds)
    val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, accessPolicyDAO, directoryDAO, cloudExt, emailDomain)

    val samRoutes = cloudExt match {
      case googleExt: GoogleExtensions => new SamRoutes(resourceService, userService, statusService, managedGroupService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with GoogleExtensionRoutes {
        val googleExtensions = googleExt
        val cloudExtensions = googleExt
      }
      case _ => new SamRoutes(resourceService, userService, statusService, managedGroupService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with NoExtensionRoutes
    }

    for {
      _ <- schemaDAO.init() recover {
        case e: WorkbenchException =>
          logger.error("FATAL - could not update schema to latest version. Is the schema lock stuck? See documentation here for more information: [link]")
          throw e
        case t: Throwable =>
          logger.error("FATAL - could not init ldap schema", t)
          throw t
      }

      _ <- Future.traverse(resourceTypes.map(_.name)) { accessPolicyDAO.createResourceType } recover {
        case t: Throwable =>
          logger.error("FATAL - unable to init resource types", t)
          throw t
      }

      _ <- cloudExt.onBoot(SamApplication(userService, resourceService, statusService))

      _ <- Http().bindAndHandle(samRoutes.route, "0.0.0.0", 8080) recover {
        case t: Throwable =>
          logger.error("FATAL - failure starting http server", t)
          throw t
      }

    } yield {

    }
  }

  startup()
}
