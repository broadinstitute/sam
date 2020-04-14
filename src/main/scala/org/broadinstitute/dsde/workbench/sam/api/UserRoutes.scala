package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
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
              traceRequest { span =>
                complete {
                  userService.createUser(createUser, span).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          } ~ requireUserInfo { user =>
            get {
              parameter("userDetailsOnly".?) { userDetailsOnly =>
                completeWithTrace {
                  userService.getUserStatus(user.userId, userDetailsOnly.exists(_.equalsIgnoreCase("true"))).map { statusOption =>
                    statusOption
                      .map { status =>
                        StatusCodes.OK -> Option(status)
                      }
                      .getOrElse(StatusCodes.NotFound -> None)
                  }
                }
              }
            }
          }
        }
      } ~ pathPrefix("v2") {
        pathPrefix("self") {
          pathEndOrSingleSlash {
            post {
              requireCreateUser { createUser =>
                traceRequest { span =>
                  complete {
                    userService.createUser(createUser, span).map(userStatus => StatusCodes.Created -> userStatus)
                  }
                }
              }
            }
          } ~ requireUserInfo { user =>
            path("info") {
              get {
                completeWithTrace { //span =>
                  userService.getUserStatusInfo(user.userId).map { statusOption =>
//                  userService.getUserStatusInfo(user.userId, span).map { statusOption =>
                    statusOption
                      .map { status =>
                        StatusCodes.OK -> Option(status)
                      }
                      .getOrElse(StatusCodes.NotFound -> None)
                  }
                }
              }
            } ~
              path("diagnostics") {
                get {
                  completeWithTrace {
                    userService.getUserStatusDiagnostics(user.userId).map { statusOption =>
                      statusOption
                        .map { status =>
                          StatusCodes.OK -> Option(status)
                        }
                        .getOrElse(StatusCodes.NotFound -> None)
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
              completeWithTrace {
                userService.getUserStatusFromEmail(WorkbenchEmail(email)).map { statusOption =>
                  statusOption
                    .map { status =>
                      StatusCodes.OK -> Option(status)
                    }
                    .getOrElse(StatusCodes.NotFound -> None)
                }
              }
            } ~
              pathPrefix(Segment) { userId =>
                pathEnd {
                  delete {
                    completeWithTrace {
                      userService.deleteUser(WorkbenchUserId(userId), userInfo).map(_ => StatusCodes.OK)
                    }
                  } ~
                    get {
                      completeWithTrace {
                        userService.getUserStatus(WorkbenchUserId(userId)).map { statusOption =>
                          statusOption
                            .map { status =>
                              StatusCodes.OK -> Option(status)
                            }
                            .getOrElse(StatusCodes.NotFound -> None)
                        }
                      }
                    }
                } ~
                  pathPrefix("enable") {
                    pathEndOrSingleSlash {
                      put {
                        completeWithTrace {
                          userService.enableUser(WorkbenchUserId(userId), userInfo).map { statusOption =>
                            statusOption
                              .map { status =>
                                StatusCodes.OK -> Option(status)
                              }
                              .getOrElse(StatusCodes.NotFound -> None)
                          }
                        }
                      }
                    }
                  } ~
                  pathPrefix("disable") {
                    pathEndOrSingleSlash {
                      put {
                        completeWithTrace {
                          userService.disableUser(WorkbenchUserId(userId), userInfo).map { statusOption =>
                            statusOption
                              .map { status =>
                                StatusCodes.OK -> Option(status)
                              }
                              .getOrElse(StatusCodes.NotFound -> None)
                          }
                        }
                      }
                    }
                  } ~
                  pathPrefix("petServiceAccount") {
                    path(Segment) { project =>
                      delete {
                        completeWithTrace {
                          cloudExtensions
                            .deleteUserPetServiceAccount(WorkbenchUserId(userId), GoogleProject(project))
                            .map(_ => StatusCodes.NoContent)
                        }
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
              completeWithTrace {
                userService.getUserIdInfoFromEmail(WorkbenchEmail(email)).map {
                  case Left(_) => StatusCodes.NotFound -> None
                  case Right(None) => StatusCodes.NoContent -> None
                  case Right(Some(userIdInfo)) => StatusCodes.OK -> Some(userIdInfo)
                }
              }
            }
          }
        } ~
          pathPrefix("invite") {
            post {
              path(Segment) { inviteeEmail =>
                completeWithTrace {
                  userService
                    .inviteUser(InviteUser(genWorkbenchUserId(System.currentTimeMillis()), WorkbenchEmail(inviteeEmail.trim)))
                    .map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          }
      }
    }
  }
}

final case class InviteUser(inviteeId: WorkbenchUserId, inviteeEmail: WorkbenchEmail)
