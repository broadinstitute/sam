package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.{pathPrefix, _}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.TosService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

trait TermsOfServiceRoutes {
  val tosService: TosService
  implicit val executionContext: ExecutionContext

  @deprecated("Being replaced by REST-ful termsOfService routes")
  def oldTermsOfServiceRoutes: server.Route =
    pathPrefix("tos") {
      path("text") {
        pathEndOrSingleSlash {
          get {
            complete(tosService.termsOfServiceText)
          }
        }
      }
    } ~
      pathPrefix("privacy") {
        path("text") {
          pathEndOrSingleSlash {
            get {
              complete(tosService.privacyPolicyText)
            }
          }
        }
      }

  def publicTermsOfServiceRoutes: server.Route =
    pathPrefix("termsOfService" / "v1")(Routes.publicTermsOfServiceV1Routes)

  def userTermsOfServiceRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("termsOfService" / "v1" / "user")(Routes.userTermsOfServiceV1Routes(samUser, samRequestContext))

  private object Routes {
    private val samUserIdPattern: Regex = "^[a-zA-Z0-9]+$".r

    // /termsOfService/v1
    def publicTermsOfServiceV1Routes: server.Route =
      concat(
        pathEndOrSingleSlash(Actions.getCurrentTermsOfService),
        pathPrefix("docs")(Routes.termsOfServiceDocRoutes)
      )

    // /api/termsOfService/v1/user
    def userTermsOfServiceV1Routes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      concat(
        pathPrefix("self")(Actions.getTermsOfServiceDetailsForSelf(samUser, samRequestContext)),
        pathPrefix("accept")(pathEndOrSingleSlash(Actions.acceptTermsOfServiceForUser(samUser, samRequestContext))),
        pathPrefix("reject")(pathEndOrSingleSlash(Actions.rejectTermsOfServiceForUser(samUser, samRequestContext))),
        pathPrefix(Segment)(userId => Routes.termsOfServiceForUserRoutes(userId, samUser, samRequestContext))
      )

    // /termsOfService/v1/docs
    private def termsOfServiceDocRoutes: server.Route =
      concat(
        pathEndOrSingleSlash(Actions.getTermsOfServiceDocs),
        pathPrefix("redirect")(termsOfServiceDocsRedirectRoutes)
      )

    // /termsOfService/v1/docs/redirect
    private def termsOfServiceDocsRedirectRoutes: server.Route =
      concat(
        pathEndOrSingleSlash(Actions.getTermsOfServiceDocsRedirect)
      )

    // /api/termsOfService/v1/user
    private def termsOfServiceForUserRoutes(rawUserId: String, requestingSamUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      validate(samUserIdPattern.matches(rawUserId), "User ID must be alpha numeric") {
        val requestedUserId = WorkbenchUserId(rawUserId)
        concat(
          pathEndOrSingleSlash(Actions.getUsersTermsOfServiceDetails(requestedUserId, requestingSamUser, samRequestContext)),
          pathPrefix("history")(Actions.getTermsOfServiceHistoryForUser(requestedUserId, samRequestContext))
        )
      }
  }

  private object Actions {
    def getCurrentTermsOfService: server.Route =
      get {
        complete(StatusCodes.NotImplemented)
      }

    def getTermsOfServiceDocs: server.Route =
      get {
        complete(StatusCodes.NotImplemented)
      }

    def getTermsOfServiceDocsRedirect: server.Route =
      get {
        complete(StatusCodes.NotImplemented)
      }

    def getUsersTermsOfServiceDetails(requestedUserId: WorkbenchUserId, requestingUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      get {
        complete(StatusCodes.OK, tosService.getTermsOfServiceDetails(requestedUserId, requestingUser, samRequestContext))
      }

    def getTermsOfServiceDetailsForSelf(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      getUsersTermsOfServiceDetails(samUser.id, samUser, samRequestContext)

    def acceptTermsOfServiceForUser(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      put {
        complete(StatusCodes.NotImplemented)
      }

    def rejectTermsOfServiceForUser(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
      put {
        complete(StatusCodes.NotImplemented)
      }

    def getTermsOfServiceHistoryForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): server.Route =
      get {
        complete(StatusCodes.NotImplemented)
      }
  }
}
