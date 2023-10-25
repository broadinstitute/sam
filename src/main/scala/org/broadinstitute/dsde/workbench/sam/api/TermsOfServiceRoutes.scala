package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.{pathPrefix, _}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.model.SamUserTos
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.TosService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.time.Instant
import scala.concurrent.ExecutionContext

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

  def userTermsOfServiceRoutes(samRequestContext: SamRequestContext): server.Route =
    pathPrefix("termsOfService" / "v1" / "user")(Routes.userTermsOfServiceV1Routes(samRequestContext))

  private object Routes {
    // /termsOfService/v1
    def publicTermsOfServiceV1Routes: server.Route =
      concat(
        pathEndOrSingleSlash(Actions.getCurrentTermsOfService),
        pathPrefix("docs")(Routes.termsOfServiceDocRoutes)
      )

    // /api/termsOfService/v1/user
    def userTermsOfServiceV1Routes(samRequestContext: SamRequestContext): server.Route =
      concat(
        pathPrefix("self")(Actions.getTermsOfServiceDetailsForSelf(samRequestContext)),
        pathPrefix("accept")(pathEndOrSingleSlash(Actions.acceptTermsOfServiceForUser(samRequestContext))),
        pathPrefix("reject")(pathEndOrSingleSlash(Actions.rejectTermsOfServiceForUser(samRequestContext))),
        pathPrefix(Segment)(userId => Routes.termsOfServiceUserRoutes(userId, samRequestContext))
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
    private def termsOfServiceUserRoutes(userId: String, samRequestContext: SamRequestContext): server.Route =
      concat(
        pathEndOrSingleSlash(Actions.getUsersTermsOfServiceDetails(userId, samRequestContext)),
        pathPrefix("history")(Actions.getTermsOfServiceHistoryForUser(userId, samRequestContext))
      )
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

    def getUsersTermsOfServiceDetails(userId: String, samRequestContext: SamRequestContext): server.Route =
      get {
//        val shellOfAUser = SamUser(WorkbenchUserId(userId), None, WorkbenchEmail(""), None, enabled = false)
//        tosService.getTermsOfServiceDetails(shellOfAUser, samRequestContext)
        complete(StatusCodes.OK, SamUserTos(WorkbenchUserId("foo"), "v123", "", Instant.now))
      }

    def getTermsOfServiceDetailsForSelf(samRequestContext: SamRequestContext): server.Route =
      // Get UserId from headers
      getUsersTermsOfServiceDetails("getFromHeaders", samRequestContext)

    def acceptTermsOfServiceForUser(samRequestContext: SamRequestContext): server.Route =
      put {
        complete(StatusCodes.NotImplemented)
      }

    def rejectTermsOfServiceForUser(samRequestContext: SamRequestContext): server.Route =
      put {
        complete(StatusCodes.NotImplemented)
      }

    def getTermsOfServiceHistoryForUser(userId: String, samRequestContext: SamRequestContext): server.Route =
      get {
        complete(StatusCodes.NotImplemented)
      }
  }
}
