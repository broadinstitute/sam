package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, Directives, MalformedRequestContentRejection}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.RejectionHandlers.termsOfServiceRejectionHandler
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, TermsOfServiceAcceptance}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** Directives to get user information.
  */
trait SamUserDirectives {
  val userService: UserService
  val cloudExtensions: CloudExtensions
  val tosService: TosService
  val termsOfServiceConfig: TermsOfServiceConfig

  /** Extracts authentication information from headers, looks up user in database, returns user only if the user is enabled and has accepted latest terms of
    * service. Throws 401 exception if user has not accepted latest terms of service or is disabled. Throws 403 exception if user does not exist (not 404
    * because that would mean the requested URL does not exist).
    * @param samRequestContext
    * @return
    */
  def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser]

  /** Extracts authentication information from headers, looks up user in database, returns user regardless of enabled or terms of service status. Specifically
    * named to be clear that inactive users are permitted. Throws 403 exception if user does not exist (not 404 because that would mean the requested URL does
    * not exist).
    * @param samRequestContext
    * @return
    */
  def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser]

  def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser]

  def asWorkbenchAdmin(samUser: SamUser): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isWorkbenchAdmin(samUser.email)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }

  def withTermsOfServiceAcceptance: Directive0 =
    Directives.mapInnerRoute { r =>
      handleRejections(termsOfServiceRejectionHandler(termsOfServiceConfig.url)) {
        requestEntityPresent {
          entity(as[TermsOfServiceAcceptance]) { tos =>
            if (tos.value.equalsIgnoreCase(termsOfServiceConfig.url)) r
            else
              reject(
                MalformedRequestContentRejection(s"Invalid ToS acceptance", new WorkbenchException(s"ToS URL did not match ${termsOfServiceConfig.url}"))
              )
          }
        }
      }
    }

  def asSamSuperAdmin(user: SamUser): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isSamSuperAdmin(user.email)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be a super admin.")))
        else r
      }
    }
}
