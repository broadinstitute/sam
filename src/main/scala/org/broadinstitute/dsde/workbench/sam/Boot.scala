package org.broadinstitute.dsde.workbench.sam

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.Pem
import org.broadinstitute.dsde.workbench.google.{HttpGoogleDirectoryDAO, HttpGoogleIamDAO, HttpGooglePubSubDAO, HttpGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")
    val googleServicesConfigOption = config.getAs[GoogleServicesConfig]("googleServices")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val accessPolicyDAO = new JndiAccessPolicyDAO(directoryConfig)
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDAO = new JndiSchemaDAO(directoryConfig)

    val resourceTypes = config.as[Map[ResourceTypeName, ResourceType]]("resourceTypes")

    val cloudExt = googleServicesConfigOption match {
      case Some(googleServicesConfig) =>
        val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
        val googleDirectoryDAO = new HttpGoogleDirectoryDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile), Option(googleServicesConfig.subEmail)), "google")
        val googleIamDAO = new HttpGoogleIamDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google")
        val googlePubSubDAO = new HttpGooglePubSubDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google", googleServicesConfig.groupSyncPubSubProject)
        val googleStorageDAO = new HttpGoogleStorageDAO(googleServicesConfig.appName, Pem(WorkbenchEmail(googleServicesConfig.serviceAccountClientId), new File(googleServicesConfig.pemFile)), "google")
        val googleKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, googlePubSubDAO, googleServicesConfig, petServiceAccountConfig)

        new GoogleExtensions(directoryDAO, accessPolicyDAO, googleDirectoryDAO, googlePubSubDAO, googleIamDAO, googleStorageDAO, googleKeyCache, googleServicesConfig, petServiceAccountConfig, resourceTypes(CloudExtensions.resourceTypeName))
      case None => NoExtensions
    }

    val resourceService = new ResourceService(resourceTypes, accessPolicyDAO, directoryDAO, cloudExt, config.getString("googleServices.appsDomain"))
    val userService = new UserService(directoryDAO, cloudExt)
    val statusService = new StatusService(directoryDAO, cloudExt, 10 seconds)

    val samRoutes = cloudExt match {
      case googleExt: GoogleExtensions => new SamRoutes(resourceService, userService, statusService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with GoogleExtensionRoutes {
        val googleExtensions = googleExt
        val cloudExtensions = googleExt
      }
      case _ => new SamRoutes(resourceService, userService, statusService, config.as[SwaggerConfig]("swagger"), directoryDAO) with StandardUserInfoDirectives with NoExtensionRoutes
    }

    for {
      _ <- schemaDAO.init() recover {
        case t: Throwable =>
          logger.error("FATAL - could not init ldap schema", t)
          throw t
      }

      _ <- Future.traverse(configResourceTypes.map(_.name)) { accessPolicyDAO.createResourceType } recover {
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
