package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.google.HttpGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam._
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, StatusService, UserService}

import scala.concurrent.duration._

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")
    val googleDirectoryConfig = config.as[GoogleDirectoryConfig]("googleDirectory")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val accessPolicyDAO = new JndiAccessPolicyDAO(directoryConfig)
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)
    val googleDirectoryDAO = new HttpGoogleDirectoryDAO(googleDirectoryConfig.clientSecrets, googleDirectoryConfig.pemFile, googleDirectoryConfig.appsDomain, googleDirectoryConfig.appName, googleDirectoryConfig.serviceProject, "google")

    val resourceService = new ResourceService(accessPolicyDAO, directoryDAO, config.getString("googleDirectory.appsDomain"))
    val userService = new UserService(directoryDAO, googleDirectoryDAO, googleDirectoryConfig.appsDomain)
    val statusService = new StatusService(directoryDAO, googleDirectoryDAO, 10 seconds)

    val configResourceTypes = config.as[Set[ResourceType]]("resourceTypes")
    val samRoutes = new SamRoutes(resourceService, userService, statusService, config.as[SwaggerConfig]("swagger")) with StandardUserInfoDirectives {
      override val resourceTypes: Map[ResourceTypeName, ResourceType] = configResourceTypes.map(rt => rt.name -> rt).toMap
    }

    for {
      _ <- accessPolicyDAO.init() recover {
        case t: Throwable =>
          logger.error("FATAL - could not init access policy dao", t)
          throw t
      }

      _ <- directoryDAO.init() recover {
        case t: Throwable =>
          logger.error("FATAL - could not init directory dao", t)
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
