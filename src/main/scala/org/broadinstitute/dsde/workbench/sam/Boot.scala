package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.google.{HttpGoogleDirectoryDAO, HttpGoogleIamDAO}
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, StatusService, UserService}

import scala.concurrent.Future
import scala.concurrent.duration._

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")
    val googleDirectoryConfig = config.as[GoogleServicesConfig]("googleServices")
    val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val accessPolicyDAO = new JndiAccessPolicyDAO(directoryConfig)
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDAO = new JndiSchemaDAO(directoryConfig)
    val googleDirectoryDAO = new HttpGoogleDirectoryDAO(googleDirectoryConfig.serviceAccountClientId, googleDirectoryConfig.pemFile, googleDirectoryConfig.subEmail, googleDirectoryConfig.appsDomain, googleDirectoryConfig.appName, "google")
    val googleIamDAO = new HttpGoogleIamDAO(googleDirectoryConfig.serviceAccountClientId, googleDirectoryConfig.pemFile, googleDirectoryConfig.appName, "google")

    val configResourceTypes = config.as[Set[ResourceType]]("resourceTypes")
    val resourceService = new ResourceService(configResourceTypes.map(rt => rt.name -> rt).toMap, accessPolicyDAO, directoryDAO, config.getString("googleServices.appsDomain"))
    val userService = new UserService(directoryDAO, googleDirectoryDAO, googleIamDAO, googleDirectoryConfig.appsDomain, petServiceAccountConfig)
    val statusService = new StatusService(directoryDAO, googleDirectoryDAO, 10 seconds)

    val samRoutes = new SamRoutes(resourceService, userService, statusService, config.as[SwaggerConfig]("swagger"),directoryDAO) with StandardUserInfoDirectives

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
