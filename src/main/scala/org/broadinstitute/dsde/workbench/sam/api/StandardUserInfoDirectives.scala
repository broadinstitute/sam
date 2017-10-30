package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future



trait StandardUserInfoDirectives extends UserInfoDirectives {
  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
    headerValueByName("OIDC_CLAIM_user_id") &
    headerValueByName("OIDC_CLAIM_expires_in") &
    headerValueByName("OIDC_CLAIM_email")
  ) tflatMap {
    case (token, userId, expiresIn, email) => {
      onSuccess(getWorkbenchUserEmailId(email)).map {
        case Some(resourceType) => UserInfo(token, resourceType.id, resourceType.email, expiresIn.toLong)
        case None => UserInfo(token, WorkbenchUserId(userId), WorkbenchUserEmail(email), expiresIn.toLong)
      }
    }
  }

  private def getWorkbenchUserEmailId(email:String):Future[Option[WorkbenchUser]] = {
    if (email.endsWith("gserviceaccount.com"))
      directoryDAO.loadUser(WorkbenchUserId(email.replaceFirst("pet-","").replace("@\\S+","")))
    else
      Future(None)
  }
}
