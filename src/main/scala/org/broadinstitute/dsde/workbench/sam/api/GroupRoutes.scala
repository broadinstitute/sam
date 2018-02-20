package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}

import scala.concurrent.ExecutionContext

/**
  * Created by gpolumbo on 2/20/2018.
  */
trait GroupRoutes extends UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val userService: UserService

  def groupRoutes: server.Route =
    pathPrefix("group") {
      requireUserInfo { userInfo =>
        pathPrefix(Segment) { groupName =>
          pathEndOrSingleSlash {
            get {
              complete {
                //            HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>")
                StatusCodes.OK -> "Hello Group"
              }
            }
          }
        }
      }
    }
}
