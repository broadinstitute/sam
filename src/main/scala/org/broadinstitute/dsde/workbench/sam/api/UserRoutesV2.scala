package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, Route}
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserResponse._
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserResponse}
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

  def userRoutesV2(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("users") {
      pathPrefix("v2") {
        pathPrefix("self") {
          pathEndOrSingleSlash {
            getSamUserResponse(samUser, samRequestContext)
          }
        } ~
          pathPrefix(Segment) { samUserId =>
            pathEndOrSingleSlash {
              if (samUser.id.value.equals(samUserId)) {
                getSamUserResponse(samUser, samRequestContext)
              } else {
                getAdminSamUserResponse(samUser, WorkbenchUserId(samUserId), samRequestContext)

              }
            }
          }
      }
    }

  private def getAdminSamUserResponse(callingSamUser: SamUser, samUserId: WorkbenchUserId, samRequestContext: SamRequestContext): Route =
    (changeForbiddenToNotFound & asWorkbenchAdmin(callingSamUser)) {
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
    }

  private def getSamUserResponse(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    get {
      complete {
        samUserResponse(samUser, samRequestContext).map(response => StatusCodes.OK -> response)
      }
    }
  private def samUserResponse(samUser: SamUser, samRequestContext: SamRequestContext): IO[SamUserResponse] =
    for {
      allowed <- userService.userAllowedToUseSystem(samUser, samRequestContext)
    } yield SamUserResponse(samUser, allowed)

}
