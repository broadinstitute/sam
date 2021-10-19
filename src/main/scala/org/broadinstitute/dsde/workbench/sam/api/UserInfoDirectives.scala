package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
//import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceAcceptance
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Directives to get user information.
  */
trait UserInfoDirectives {
  val directoryDAO: DirectoryDAO
  val cloudExtensions: CloudExtensions
  val termsOfServiceConfig: TermsOfServiceConfig

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo]

  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[CreateWorkbenchUser]

  def asWorkbenchAdmin(userInfo: UserInfo): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isWorkbenchAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }
//
//  def withTermsOfServiceAcceptance(tos: TermsOfServiceAcceptance): Directive0 = {
//    val failDirective = Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"You must accept the Terms of Service in order to register. See ${termsOfServiceConfig.url}")))
//
//    Directives.mapInnerRoute { r =>
//      if (!termsOfServiceConfig.enabled || tos.url.equalsIgnoreCase(termsOfServiceConfig.url))
//        r
//      else
//        failDirective
//    }
//  }

}
