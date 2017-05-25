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
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

object Boot extends App with LazyLogging {

  //TODO: This is here now for convenience, will move to appropriate place soon
  case class Action(actionName: String)
  case class Role(roleName: String, actions: Set[Action])
  case class Resource(resourceType: String, roles: Set[Role])

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

    //TODO: There are some promising Scala wrappers for Typesafe Config (see https://github.com/kxbmap/configs)
    //If this looks too unmaintainable or messy, explore using a wrapper instead of hacking our own
    val resources: Set[Resource] = {
      val resourcesConfigs = ConfigFactory.load("resources.conf").getConfig("resources")

      resourcesConfigs.root.asScala.map { case (resourceType, resourceConfigObj: ConfigObject) =>
        val resourceConfig = resourceConfigObj.toConfig
        val roles = resourceConfig.getConfig("roles").root().asScala.map { case (roleName, roleConfigObj: ConfigObject) =>
          val roleConfig = roleConfigObj.toConfig
          val actions = roleConfig.getStringList("actions").asScala.map(Action).toSet
          Role(roleName, actions)
        }.toSet

        Resource(resourceType, roles)
      }
    }.toSet

    def syncResources(resources: Set[Resource]): Unit = {
      //TODO: This is where we'd sync the resources from config
      logger.debug("Syncing resources...")
    }

    //We want to make sure that OpenAM is in sync with the resources that we have defined in config. Do that before booting
    syncResources(resources)

    Http().bindAndHandle(new SamRoutes(resourceService).route, "localhost", 8080)
  }

  startup()
}
