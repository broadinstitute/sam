package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** Created by tlangs on 10/12/2023.
  */
trait UserRoutesV3 extends SamUserDirectives with SamRequestContextDirectives {
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

  def userRoutesV3(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("users") {
      pathPrefix("v2") {
        pathPrefix("self") {
          pathEndOrSingleSlash {
            get {
              complete {
                StatusCodes.OK -> samUser
              }
            }
          }
        } ~
          pathPrefix(Segment) { samUserId =>
            pathEndOrSingleSlash {
              val workbenchUserId = WorkbenchUserId(samUserId)
              if (workbenchUserId.equals(samUser.id)) {
                get {
                  complete {
                    StatusCodes.OK -> samUser
                  }
                }
              } else {
                (changeForbiddenToNotFound & asWorkbenchAdmin(samUser)) {
                  get {
                    complete {
                      userService.getUser(WorkbenchUserId(samUserId), samRequestContext).map(user => (if (user.isDefined) OK else NotFound) -> user)
                    }
                  }
                }
              }
            }
          }
      }
    }
}
