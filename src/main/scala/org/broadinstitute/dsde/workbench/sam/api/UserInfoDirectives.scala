package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, Directives, MalformedRequestContentRejection}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.RejectionHandlers.{MethodDisabled, termsOfServiceRejectionHandler}
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceAcceptance
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext


/**
  * Directives to get user information.
  */
trait UserInfoDirectives {
  val directoryDAO: DirectoryDAO
  val registrationDAO: RegistrationDAO
  val cloudExtensions: CloudExtensions
  val termsOfServiceConfig: TermsOfServiceConfig

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo]

  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[WorkbenchUser]

  def asWorkbenchAdmin(userInfo: UserInfo): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isWorkbenchAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }

  def withTermsOfServiceAcceptance: Directive0 = {
    Directives.mapInnerRoute { r =>
        handleRejections(termsOfServiceRejectionHandler(termsOfServiceConfig.url)) {
          if (termsOfServiceConfig.enabled) {
          requestEntityPresent {
            entity(as[TermsOfServiceAcceptance]) { tos =>
              if (tos.value.equalsIgnoreCase(termsOfServiceConfig.url)) r
              else reject(MalformedRequestContentRejection(s"Invalid ToS acceptance", new WorkbenchException(s"ToS URL did not match ${termsOfServiceConfig.url}")))
            }
          }
        } else reject(MethodDisabled("Terra Terms of Service is disabled."))
      }
    }
  }

}
