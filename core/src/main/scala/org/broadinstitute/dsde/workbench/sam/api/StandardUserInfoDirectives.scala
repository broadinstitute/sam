package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.headerValueByName
import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

trait StandardUserInfoDirectives extends UserInfoDirectives {
  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
    headerValueByName("OIDC_CLAIM_user_id") &
    headerValueByName("OIDC_CLAIM_expires_in") &
    headerValueByName("OIDC_CLAIM_email")
  ).tmap { case (token, userId, expiresIn, email) => UserInfo(token, WorkbenchUserId(userId), WorkbenchUserEmail(email), expiresIn.toLong) }
}
