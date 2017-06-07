package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, UserInfo}
import org.broadinstitute.dsde.workbench.sam.openam.{OpenAmDAO, _}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

import scala.concurrent.{ExecutionContext, Future}

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val openAmConfig = config.as[OpenAmConfig]("openam")

    val accessManagementDAO = new OpenAmDAO(openAmConfig, directoryConfig)
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)

    val resourceService = new ResourceService(accessManagementDAO, directoryDAO)

    //Before booting, sync resource types in config with OpenAM
    val resourceTypes = config.as[Set[ResourceType]]("resourceTypes")
    syncResourceTypes(resourceTypes, resourceService).recover {
      case t: Throwable => logger.error("failure syncing resource types", t)
    }

    val samRoutes = new SamRoutes(resourceService) with StandardUserInfoDirectives

    Http().bindAndHandle(samRoutes.route, "localhost", 8080)
  }

  private def syncResourceTypes(resources: Set[ResourceType], resourceService: ResourceService)(implicit executionContext: ExecutionContext): Future[Map[String, ResourceType]] = {
    logger.info("Syncing resource types...")
    for {
      authToken <- resourceService.getOpenAmAdminAccessToken()
      uuidAndResourceType <- Future.traverse(resources) { resourceType =>
        resourceService.createResourceType(resourceType, UserInfo(authToken)).map(_ -> resourceType)
      }
    } yield uuidAndResourceType.toMap
  }

  startup()
}
