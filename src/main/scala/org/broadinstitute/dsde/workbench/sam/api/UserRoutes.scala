package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
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
        (pathPrefix("v1") | pathEndOrSingleSlash){
          pathEndOrSingleSlash {
            post {
              complete {
                userService.createUser(WorkbenchUser(userInfo.userId, userInfo.userEmail)).map(userStatus => StatusCodes.Created -> userStatus)
              }
            } ~
            get {
              parameter("userDetailsOnly".?) { userDetailsOnly =>
                complete {
                  userService.getUserStatus(userInfo.userId, userDetailsOnly.exists(_.equalsIgnoreCase("true"))).map { statusOption =>
                    statusOption.map { status =>
                      StatusCodes.OK -> Option(status)
                    }.getOrElse(StatusCodes.NotFound -> None)
                  }
                }
              }
            }
          }
        } ~
        pathPrefix("v2") {
          pathPrefix("self") {
            pathEndOrSingleSlash {
              post {
                complete {
                  userService.createUser(WorkbenchUser(userInfo.userId, userInfo.userEmail)).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            } ~
            path("info") {
              get {
                complete {
                  userService.getUserStatusInfo(userInfo.userId).map { statusOption =>
                    statusOption.map { status =>
                      StatusCodes.OK -> Option(status)
                    }.getOrElse(StatusCodes.NotFound -> None)
                  }
                }
              }
            } ~
            path("diagnostics") {
              get {
                complete {
                  userService.getUserStatusDiagnostics(userInfo.userId). map { statusOption =>
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

  def adminUserRoutes: server.Route =
    pathPrefix("admin") {
      requireUserInfo { userInfo =>
        asWorkbenchAdmin(userInfo) {
          pathPrefix("user") {
            path("email" / Segment) { email =>
              complete {
                userService.getUserStatusFromEmail(WorkbenchEmail(email)).map { statusOption =>
                  statusOption.map { status =>
                    StatusCodes.OK -> Option(status)
                  }.getOrElse(StatusCodes.NotFound -> None)
                }
              }
            } ~
            pathPrefix(Segment) { userId =>
              pathEnd {
                delete {
                  complete {
                    userService.deleteUser(WorkbenchUserId(userId), userInfo).map(_ => StatusCodes.OK)
                  }
                } ~
                get {
                  complete {
                    userService.getUserStatus(WorkbenchUserId(userId)).map { statusOption =>
                      statusOption.map { status =>
                        StatusCodes.OK -> Option(status)
                      }.getOrElse(StatusCodes.NotFound -> None)
                    }
                  }
                }
              } ~
              pathPrefix("enable") {
                pathEndOrSingleSlash {
                  put {
                    complete {
                      userService.enableUser(WorkbenchUserId(userId), userInfo).map { statusOption =>
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
                      userService.disableUser(WorkbenchUserId(userId), userInfo).map { statusOption =>
                        statusOption.map { status =>
                          StatusCodes.OK -> Option(status)
                        }.getOrElse(StatusCodes.NotFound -> None)
                      }
                    }
                  }
                }
              } ~
              pathPrefix("petServiceAccount") {
                path(Segment) { project =>
                  delete {
                    complete {
                      cloudExtensions.deleteUserPetServiceAccount(WorkbenchUserId(userId), GoogleProject(project)).map(_ => StatusCodes.NoContent)
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
