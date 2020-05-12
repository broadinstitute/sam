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
import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait UserRoutes extends UserInfoDirectives with SamModelDirectives {
  implicit val executionContext: ExecutionContext
  val userService: UserService

  def userRoutes: server.Route =
    pathPrefix("user") {
      (pathPrefix("v1") | pathEndOrSingleSlash) {
        pathEndOrSingleSlash {
          post {
            withSamRequestContext { samRequestContext =>
              requireCreateUser { createUser =>
                complete {
                  userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          } ~ withSamRequestContext { samRequestContext =>
            requireUserInfo { user =>
              get {
                parameter("userDetailsOnly".?) { userDetailsOnly =>
                  complete {
                    userService.getUserStatus(user.userId, userDetailsOnly.exists(_.equalsIgnoreCase("true")), samRequestContext).map { statusOption =>
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
      } ~ pathPrefix("v2") {
        pathPrefix("self") {
          pathEndOrSingleSlash {
            post {
              withSamRequestContext { samRequestContext =>
                requireCreateUser { createUser =>
                  complete {
                    userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
                  }
                }
              }
            }
          } ~ withSamRequestContext { samRequestContext =>
            requireUserInfo { user =>
              path("info") {
                get {
                  complete {
                    userService.getUserStatusInfo(user.userId, samRequestContext).map { statusOption =>
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
                    complete {
                      userService.getUserStatusDiagnostics(user.userId, samRequestContext).map { statusOption =>
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
    }

  def adminUserRoutes: server.Route =
    pathPrefix("admin") {
      withSamRequestContext { samRequestContext =>
        requireUserInfo { userInfo =>
          asWorkbenchAdmin(userInfo) {
            pathPrefix("user") {
              path("email" / Segment) { email =>
                complete {
                  userService.getUserStatusFromEmail(WorkbenchEmail(email), samRequestContext).map { statusOption =>
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
                      complete {
                        userService.deleteUser(WorkbenchUserId(userId), userInfo, samRequestContext).map(_ => StatusCodes.OK)
                      }
                    } ~
                      get {
                        complete {
                          userService.getUserStatus(WorkbenchUserId(userId), samRequestContext = samRequestContext).map { statusOption =>
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
                          complete {
                            userService.enableUser(WorkbenchUserId(userId), userInfo, samRequestContext).map { statusOption =>
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
                          complete {
                            userService.disableUser(WorkbenchUserId(userId), userInfo, samRequestContext).map { statusOption =>
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
                          complete {
                            cloudExtensions
                              .deleteUserPetServiceAccount(WorkbenchUserId(userId), GoogleProject(project), samRequestContext)
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
    }

  val apiUserRoutes: server.Route = pathPrefix("users") {
    pathPrefix("v1") {
      withSamRequestContext { samRequestContext =>
        requireUserInfo { userInfo =>
          get {
            path(Segment) { email =>
              pathEnd {
                complete {
                  userService.getUserIdInfoFromEmail(WorkbenchEmail(email), samRequestContext).map {
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
                  complete {
                    userService
                      .inviteUser(InviteUser(genWorkbenchUserId(System.currentTimeMillis()), WorkbenchEmail(inviteeEmail.trim)), samRequestContext)
                      .map(userStatus => StatusCodes.Created -> userStatus)
                  }
                }
              }
            }
        }
      }
    }
  }
}

final case class InviteUser(inviteeId: WorkbenchUserId, inviteeEmail: WorkbenchEmail)
