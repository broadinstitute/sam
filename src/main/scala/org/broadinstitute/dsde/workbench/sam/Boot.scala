package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.api.{SamRoutes, StandardUserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, SamUserId, UserInfo}
import org.broadinstitute.dsde.workbench.sam.openam.{HttpOpenAmDAO, _}
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

    val accessManagementDAO = new HttpOpenAmDAO(openAmConfig, directoryConfig)
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)

    val resourceService = new ResourceService(accessManagementDAO, directoryDAO)

    //Before booting, sync resource types in config with OpenAM
    val resourceTypes = config.as[Set[ResourceType]]("resourceTypes")
    syncResourceTypes(resourceTypes, resourceService).recover {
      case t: Throwable =>
        logger.error("FATAL - failure syncing resource types", t)
        throw t
    } flatMap { resourceTypesWithUuids =>
      val samRoutes = new SamRoutes(resourceService) with StandardUserInfoDirectives {
        override val resourceTypes: Map[String, ResourceType] = resourceTypesWithUuids.map(rt => rt.name -> rt).toMap
      }

      Http().bindAndHandle(samRoutes.route, "0.0.0.0", 8080)
    } recover {
      case t: Throwable =>
        logger.error("FATAL - failure starting http server", t)
        throw t
    }

  }

  private def syncResourceTypes(resources: Set[ResourceType], resourceService: ResourceService)(implicit executionContext: ExecutionContext): Future[Set[ResourceType]] = {
    logger.info("Syncing resource types...")
    for {
      adminUserInfo <- resourceService.getOpenAmAdminUserInfo
      resourceTypesWithUuid <- resourceService.syncResourceTypes(resources, adminUserInfo)
    } yield resourceTypesWithUuid
  }

  startup()
}
