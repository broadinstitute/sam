package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait UserRoutesV1 extends SamUserDirectives with SamRequestContextDirectives {
  val userService: UserService

  def userRoutesV1(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = pathPrefix("users") {
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
