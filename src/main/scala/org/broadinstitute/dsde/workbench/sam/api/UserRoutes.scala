package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.completeWithTrace
import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait UserRoutes extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext
  val userService: UserService

  def userRoutes: server.Route =
    pathPrefix("user") {
      (pathPrefix("v1") | pathEndOrSingleSlash) {
        pathEndOrSingleSlash {
          post {
            requireCreateUser { createUser =>
              completeWithTrace { traceContext =>
                userService.createUser(createUser, traceContext).map(userStatus => StatusCodes.Created -> userStatus)
              }
            }
          } ~ requireUserInfo { user =>
            get {
              parameter("userDetailsOnly".?) { userDetailsOnly =>
                completeWithTrace({ traceContext =>
                  userService.getUserStatus(user.userId, traceContext, userDetailsOnly.exists(_.equalsIgnoreCase("true"))).map { statusOption =>
                    statusOption
                      .map { status =>
                        StatusCodes.OK -> Option(status)
                      }
                      .getOrElse(StatusCodes.NotFound -> None)
                  }
                })
              }
            }
          }
        }
      } ~ pathPrefix("v2") {
        pathPrefix("self") {
          pathEndOrSingleSlash {
            post {
              requireCreateUser { createUser =>
                completeWithTrace { traceContext =>
                  userService.createUser(createUser, traceContext).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          } ~ requireUserInfo { user =>
            path("info") {
              get {
                completeWithTrace({ traceContext =>
                  userService.getUserStatusInfo(user.userId, traceContext).map { statusOption =>
                    statusOption
                      .map { status =>
                        StatusCodes.OK -> Option(status)
                      }
                      .getOrElse(StatusCodes.NotFound -> None)
                  }
                })
              }
            } ~
              path("diagnostics") {
                get {
                  completeWithTrace({ traceContext =>
                    userService.getUserStatusDiagnostics(user.userId, traceContext).map { statusOption =>
                      statusOption
                        .map { status =>
                          StatusCodes.OK -> Option(status)
                        }
                        .getOrElse(StatusCodes.NotFound -> None)
                    }
                  })
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
              completeWithTrace({ traceContext =>
                userService.getUserStatusFromEmail(WorkbenchEmail(email), traceContext).map { statusOption =>
                  statusOption
                    .map { status =>
                      StatusCodes.OK -> Option(status)
                    }
                    .getOrElse(StatusCodes.NotFound -> None)
                }
              })
            } ~
              pathPrefix(Segment) { userId =>
                pathEnd {
                  delete {
                    completeWithTrace({ traceContext =>
                      userService.deleteUser(WorkbenchUserId(userId), userInfo, traceContext).map(_ => StatusCodes.OK)
                    })
                  } ~
                    get {
                      completeWithTrace({ traceContext =>
                        userService.getUserStatus(WorkbenchUserId(userId), traceContext).map { statusOption =>
                          statusOption
                            .map { status =>
                              StatusCodes.OK -> Option(status)
                            }
                            .getOrElse(StatusCodes.NotFound -> None)
                        }
                      })
                    }
                } ~
                  pathPrefix("enable") {
                    pathEndOrSingleSlash {
                      put {
                        completeWithTrace({ traceContext =>
                          userService.enableUser(WorkbenchUserId(userId), userInfo, traceContext).map { statusOption =>
                            statusOption
                              .map { status =>
                                StatusCodes.OK -> Option(status)
                              }
                              .getOrElse(StatusCodes.NotFound -> None)
                          }
                        })
                      }
                    }
                  } ~
                  pathPrefix("disable") {
                    pathEndOrSingleSlash {
                      put {
                        completeWithTrace({ traceContext =>
                          userService.disableUser(WorkbenchUserId(userId), userInfo, traceContext).map { statusOption =>
                            statusOption
                              .map { status =>
                                StatusCodes.OK -> Option(status)
                              }
                              .getOrElse(StatusCodes.NotFound -> None)
                          }
                        })
                      }
                    }
                  } ~
                  pathPrefix("petServiceAccount") {
                    path(Segment) { project =>
                      delete {
                        completeWithTrace({ traceContext =>
                          cloudExtensions
                            .deleteUserPetServiceAccount(WorkbenchUserId(userId), GoogleProject(project), traceContext)
                            .map(_ => StatusCodes.NoContent)
                        })
                      }
                    }
                  }
              }
          }
        }
      }
    }

  val apiUserRoutes: server.Route = pathPrefix("users") {
    pathPrefix("v1") {
      requireUserInfo { userInfo =>
        get {
          path(Segment) { email =>
            pathEnd {
              completeWithTrace({ traceContext =>
                userService.getUserIdInfoFromEmail(WorkbenchEmail(email), traceContext).map {
                  case Left(_) => StatusCodes.NotFound -> None
                  case Right(None) => StatusCodes.NoContent -> None
                  case Right(Some(userIdInfo)) => StatusCodes.OK -> Some(userIdInfo)
                }
              })
            }
          }
        } ~
          pathPrefix("invite") {
            post {
              path(Segment) { inviteeEmail =>
                completeWithTrace({ traceContext =>
                  userService
                    .inviteUser(InviteUser(genWorkbenchUserId(System.currentTimeMillis()), WorkbenchEmail(inviteeEmail.trim)), traceContext)
                    .map(userStatus => StatusCodes.Created -> userStatus)
                })
              }
            }
          }
      }
    }
  }
}

final case class InviteUser(inviteeId: WorkbenchUserId, inviteeEmail: WorkbenchEmail)
