package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait UserRoutes extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext
  val userService: UserService

  def userRoutes: server.Route =
    pathPrefix("user") {
      requireUserInfo { userInfo =>
        pathEndOrSingleSlash {
          post {
            complete {
              userService.createUser(SamUser(userInfo.userId, userInfo.userEmail)).map(userStatus => StatusCodes.Created -> userStatus)
            }
          } ~
          get {
            complete {
              userService.getUserStatus(SamUser(userInfo.userId, userInfo.userEmail)).map { statusOption =>
                statusOption.map { status =>
                  StatusCodes.OK -> Option(status)
                }.getOrElse(StatusCodes.NotFound -> None)
              }
            }
          }
        }
      }
    }

  def adminUserRoutes: server.Route =
    pathPrefix("admin") {
      pathPrefix("user") {
        requireUserInfo { userInfo =>
          pathPrefix(Segment) { userId =>
            pathPrefix("enable") {
              pathEndOrSingleSlash {
                put {
                  complete {
                    userService.enableUser(SamUserId(userId)).map { statusOption =>
                      statusOption.map { status =>
                        StatusCodes.OK -> Option(status)
                      }.getOrElse(StatusCodes.NotFound -> None)
                    }
                  }
                }
              }
            } ~
            pathPrefix("disable") {
              pathEndOrSingleSlash {
                put {
                  complete {
                    userService.disableUser(SamUserId(userId)).map { statusOption =>
                      statusOption.map { status =>
                        StatusCodes.OK -> Option(status)
                      }.getOrElse(StatusCodes.NotFound -> None)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

}
