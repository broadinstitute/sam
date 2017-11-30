package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.model.UserInfo
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions}
import org.broadinstitute.dsde.workbench.sam._

/**
 * Directives to get user information.
 */
trait UserInfoDirectives {
  val cloudExtensions: CloudExtensions
  def requireUserInfo: Directive1[UserInfo]

  def asWorkbenchAdmin(userInfo: UserInfo): Directive0 = {
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isWorkbenchAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }
  }

}
