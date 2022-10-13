package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/** Created by mbemis on 5/22/17.
  */
trait UserRoutes extends SamUserDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val userService: UserService

  /** Changes a 403 error to a 404 error. Used when `UserInfoDirectives` throws a 403 in the case where a user is not found. In most routes that is appropriate
    * but in the user routes it should be a 404.
    */
  private val changeForbiddenToNotFound: Directive0 = {
    import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

    handleExceptions(ExceptionHandler {
      case withErrorReport: WorkbenchExceptionWithErrorReport if withErrorReport.errorReport.statusCode.contains(StatusCodes.Forbidden) =>
        complete((StatusCodes.NotFound, withErrorReport.errorReport.copy(statusCode = Option(StatusCodes.NotFound))))
    })
  }

  def userRoutes(samRequestContext: SamRequestContext): server.Route =
    pathPrefix("user") {
      (pathPrefix("v1") | pathEndOrSingleSlash) {
        pathEndOrSingleSlash {
          post {
            withNewUser(samRequestContext) { createUser =>
              complete {
                userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
              }
            }
          } ~
            (changeForbiddenToNotFound & withUserAllowInactive(samRequestContext)) { user =>
              get {
                parameter("userDetailsOnly".?) { userDetailsOnly =>
                  complete {
                    userService.getUserStatus(user.id, userDetailsOnly.exists(_.equalsIgnoreCase("true")), samRequestContext).map { statusOption =>
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
        } ~
          pathPrefix("termsofservice") {
            pathPrefix("status") {
              pathEndOrSingleSlash {
                get {
                  withUserAllowInactive(samRequestContext) { samUser =>
                    complete {
                      tosService.getTosStatus(samUser.id, samRequestContext).map { statusOption =>
                        statusOption
                          .map { status =>
                            StatusCodes.OK -> Option(JsBoolean(status))
                          }
                          .getOrElse(StatusCodes.NotFound -> None)
                      }
                    }
                  }
                }
              }
            } ~
              pathEndOrSingleSlash {
                post {
                  withUserAllowInactive(samRequestContext) { samUser =>
                    withTermsOfServiceAcceptance {
                      complete {
                        userService.acceptTermsOfService(samUser.id, samRequestContext).map { statusOption =>
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
                  delete {
                    withUserAllowInactive(samRequestContext) { samUser =>
                      complete {
                        userService.rejectTermsOfService(samUser.id, samRequestContext).map { statusOption =>
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
              withNewUser(samRequestContext) { createUser =>
                complete {
                  userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          } ~
            (changeForbiddenToNotFound & withUserAllowInactive(samRequestContext)) { user =>
              path("info") {
                get {
                  complete {
                    userService.getUserStatusInfo(user, samRequestContext)
                  }
                }
              } ~
                path("diagnostics") {
                  get {
                    complete {
                      userService.getUserStatusDiagnostics(user.id, samRequestContext).map { statusOption =>
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

  def apiUserRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = pathPrefix("users") {
    pathPrefix("v1") {
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
                  .inviteUser(WorkbenchEmail(inviteeEmail.trim), samRequestContext)
                  .map(userStatus => StatusCodes.Created -> userStatus)
              }
            }
          }
        }
    }
  }
}
