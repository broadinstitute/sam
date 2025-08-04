package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.{TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/** Created by mbemis on 5/22/17.
  */
trait OldUserRoutes extends SamUserDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val userService: UserService
  val tosService: TosService

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

  def oldUserRoutes(samRequestContext: SamRequestContext): server.Route =
    pathPrefix("user") {
      (pathPrefix("v1") | pathEndOrSingleSlash) {
        pathEndOrSingleSlash {
          postWithTelemetry(samRequestContext) {
            withNewUser(samRequestContext) { createUser =>
              complete {
                userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
              }
            }
          } ~
          (changeForbiddenToNotFound & withUserAllowInactive(samRequestContext)) { user =>
            getWithTelemetry(samRequestContext) {
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
              getWithTelemetry(samRequestContext) {
                withUserAllowInactive(samRequestContext) { samUser =>
                  complete {
                    tosService.getTermsOfServiceComplianceStatus(samUser, samRequestContext).map { tosAcceptanceStatus =>
                      StatusCodes.OK -> Option(JsBoolean(tosAcceptanceStatus.permitsSystemUsage))
                    }
                  }
                }
              }
            }
          } ~
          pathEndOrSingleSlash {
            postWithTelemetry(samRequestContext) {
              withUserAllowInactive(samRequestContext) { samUser =>
                withTermsOfServiceAcceptance {
                  complete {
                    userService.acceptTermsOfService(samUser.id, samRequestContext).map { userStatusOption =>
                      userStatusOption
                        .map { status =>
                          StatusCodes.OK -> Option(status)
                        }
                        .getOrElse(StatusCodes.NotFound -> None)
                    }
                  }
                }
              }
            } ~
            deleteWithTelemetry(samRequestContext) {
              withUserAllowInactive(samRequestContext) { samUser =>
                complete {
                  userService.rejectTermsOfService(samUser.id, samRequestContext).map { userStatusOption =>
                    userStatusOption
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
            postWithTelemetry(samRequestContext) {
              withNewUser(samRequestContext) { createUser =>
                complete {
                  userService.createUser(createUser, samRequestContext).map(userStatus => StatusCodes.Created -> userStatus)
                }
              }
            }
          } ~
          (changeForbiddenToNotFound & withUserAllowInactive(samRequestContext)) { user =>
            path("info") {
              getWithTelemetry(samRequestContext) {
                complete {
                  userService.getUserStatusInfo(user, samRequestContext)
                }
              }
            } ~
            path("diagnostics") {
              getWithTelemetry(samRequestContext) {
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
            } ~
            path("termsOfServiceDetails") {
              getWithTelemetry(samRequestContext) {
                complete(tosService.getTosDetails(user, samRequestContext))
              }
            } ~
            path("termsOfServiceComplianceStatus") {
              getWithTelemetry(samRequestContext) {
                complete(tosService.getTermsOfServiceComplianceStatus(user, samRequestContext))
              }
            }
          }
        }
      }
    }
}
