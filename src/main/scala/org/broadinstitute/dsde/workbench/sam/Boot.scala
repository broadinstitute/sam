package org.broadinstitute.dsde.workbench.sam

import scala.collection.JavaConverters._
import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.{ConfigFactory, ConfigObject}
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes
import net.ceedubs.ficus.Ficus._
import directory._
import org.broadinstitute.dsde.workbench.sam.dataaccess.{AccessManagementDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamModels._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

import scala.concurrent.Future

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val conf = ConfigFactory.load()

    val accessManagementDAO = new AccessManagementDAO(conf.getConfig("openam").getString("server"))
    val directoryDAO = new DirectoryDAO(conf.getConfig("opendj").getString("server"))

    val resourceService = new ResourceService(accessManagementDAO, directoryDAO)

    //TODO: Look into Scala friendly wrappers for Typesafe Config (like https://github.com/kxbmap/configs)
    val resources: Set[Resource] = {
      val resourcesConfig = ConfigFactory.load("resources.conf").getConfig("resources")

      resourcesConfig.root.asScala.map { case (resourceType, resourceConfigObj: ConfigObject) =>
        val resourceConfig = resourceConfigObj.toConfig
        val roles = resourceConfig.getConfig("roles").root().asScala.map { case (roleName, roleConfigObj: ConfigObject) =>
          val roleConfig = roleConfigObj.toConfig
          val actions = roleConfig.getStringList("actions").asScala.map(ResourceAction).toSet
          ResourceRole(roleName, actions)
        }.toSet

        Resource(resourceType, roles)
      }
    }.toSet

    def syncResourceTypes(resources: Set[Resource]): Unit = {
      logger.warn("Syncing resources...")
      Future.traverse(resources) { case resource =>
        resourceService.createResourceType(resource)
      }
    }

    //Before booting, sync resource types in config with OpenAM
    syncResourceTypes(resources)

    Http().bindAndHandle(new SamRoutes(resourceService).route, "localhost", 8080)
  }

  startup()
}
