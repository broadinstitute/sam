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

  def groupRoutes: server.Route = requireUserInfo { userInfo =>
    path("group" / Segment) { groupName =>
      get {
        complete {
          StatusCodes.OK -> s"Group Name: $groupName"
        }
      } ~
      post {
        complete {
          StatusCodes.OK -> s"Posted new Group: $groupName"
        }
      } ~
      delete {
        complete {
          StatusCodes.OK -> s"Deleted Group: $groupName"
        }
      }
    }
  }
}
