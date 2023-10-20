package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{NotFound, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, Route}
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserResponse._
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAttributesRequest, SamUserResponse}
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** Created by tlangs on 10/12/2023.
  */
trait UserRoutesV2 extends SamUserDirectives with SamRequestContextDirectives {
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

  // These routes are wrapped in `withUserAllowInactive` because a user should be able to get info about themselves
  // Routes that need the user to be active should be wrapped in a directive, such as `withActiveUser`, to ensure
  // that the user is allowed to use the system.
  def userRoutesV2(samRequestContextWithoutUser: SamRequestContext): Route =
    withUserAllowInactive(samRequestContextWithoutUser) { samUser: SamUser =>
      val samRequestContext = samRequestContextWithoutUser.copy(samUser = Some(samUser))
      pathPrefix("users") {
        pathPrefix("v2") {
          pathPrefix("self") {
            // api/users/v2/self
            pathEndOrSingleSlash {
              getSamUserResponse(samUser, samRequestContext)
            } ~
              // api/users/v2/self/allowed
              pathPrefix("allowed") {
                pathEndOrSingleSlash {
                  getSamUserAllowances(samUser, samRequestContext)
                }
              } ~
              // api/user/v2/self/attributes
              pathPrefix("attributes") {
                pathEndOrSingleSlash {
                  getSamUserAttributes(samUser, samRequestContext) ~
                    patchSamUserAttributes(samUser, samRequestContext)
                }
              }
          } ~
            pathPrefix(Segment) { samUserId =>
              val workbenchUserId = WorkbenchUserId(samUserId)
              // api/users/v2/{sam_user_id}
              pathEndOrSingleSlash {
                regularUserOrAdmin(samUser, workbenchUserId, samRequestContext)(getSamUserResponse)(getAdminSamUserResponse)
              } ~
                // api/users/v2/{sam_user_id}/allowed
                pathPrefix("allowed") {
                  pathEndOrSingleSlash {
                    regularUserOrAdmin(samUser, workbenchUserId, samRequestContext)(getSamUserAllowances)(getAdminSamUserAllowances)
                  }
                }
            }
        }
      }
    }

  private def regularUserOrAdmin(callingUser: SamUser, requestedUserId: WorkbenchUserId, samRequestContext: SamRequestContext)(
      asRegular: (SamUser, SamRequestContext) => Route
  )(asAdmin: (WorkbenchUserId, SamRequestContext) => Route): Route =
    if (callingUser.id.equals(requestedUserId)) {
      asRegular(callingUser, samRequestContext)
    } else {
      (changeForbiddenToNotFound & asWorkbenchAdmin(callingUser)) {
        asAdmin(requestedUserId, samRequestContext)
      }
    }

  // Get Sam User
  private def getAdminSamUserResponse(samUserId: WorkbenchUserId, samRequestContext: SamRequestContext): Route =
    get {
      complete {
        for {
          user <- userService.getUser(samUserId, samRequestContext)
          response <- user match {
            case Some(value) => samUserResponse(value, samRequestContext).map(Some(_))
            case None => IO(None)
          }
        } yield (if (response.isDefined) OK else NotFound) -> response
      }
    }

  private def getSamUserResponse(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    get {
      complete {
        samUserResponse(samUser, samRequestContext).map(response => StatusCodes.OK -> response)
      }
    }
  private def samUserResponse(samUser: SamUser, samRequestContext: SamRequestContext): IO[SamUserResponse] =
    for {
      allowances <- userService.getUserAllowances(samUser, samRequestContext)
    } yield SamUserResponse(samUser, allowances.allowed)

  // Get Sam User Allowed
  private def getSamUserAllowances(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    get {
      complete {
        userService.getUserAllowances(samUser, samRequestContext).map(StatusCodes.OK -> _)
      }
    }

  private def getAdminSamUserAllowances(samUserId: WorkbenchUserId, samRequestContext: SamRequestContext): Route =
    get {
      complete {
        for {
          user <- userService.getUser(samUserId, samRequestContext)
          response <- user match {
            case Some(value) => userService.getUserAllowances(value, samRequestContext).map(Some(_))
            case None => IO(None)
          }
        } yield (if (response.isDefined) OK else NotFound) -> response
      }
    }

  private def getSamUserAttributes(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    get {
      complete {
        userService.getUserAttributes(samUser.id, samRequestContext).map(response => (if (response.isDefined) OK else NotFound) -> response)
      }
    }

  private def patchSamUserAttributes(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    patch {
      entity(as[SamUserAttributesRequest]) { userAttributesRequest =>
        complete {
          userService.setUserAttributesFromRequest(samUser.id, userAttributesRequest, samRequestContext).map(OK -> _)
        }
      }
    }
}
