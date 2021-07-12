package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Directives to get user information.
  */
trait UserInfoDirectives {
  val directoryDAO: DirectoryDAO
  val cloudExtensions: CloudExtensions

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo]

  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[CreateWorkbenchUser]

  def asWorkbenchAdmin(userInfo: UserInfo): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isWorkbenchAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be an admin.")))
        else r
      }
    }

  def asSamSuperAdmin(userInfo: UserInfo): Directive0 =
    Directives.mapInnerRoute { r =>
      onSuccess(cloudExtensions.isSamSuperAdmin(userInfo.userEmail)) { isAdmin =>
        if (!isAdmin) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "You must be a super admin.")))
        else r
      }
    }

}
