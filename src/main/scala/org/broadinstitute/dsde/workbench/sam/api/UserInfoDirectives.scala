package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.model.UserInfo
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam._

/**
 * Directives to get user information.
 */
trait UserInfoDirectives {
  val userService: UserService
  def requireUserInfo: Directive1[UserInfo]

  def asWorkbenchAdmin(userInfo: UserInfo): Directive0 = {
    Directives.mapInnerRoute { r =>
      onSuccess(userService.tryIsWorkbenchAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }
  }

}
