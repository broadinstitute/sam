package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.headerValueByName
import org.broadinstitute.dsde.workbench.sam.model.{SamUserEmail, SamUserId, UserInfo}

trait StandardUserInfoDirectives extends UserInfoDirectives {

  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
    headerValueByName("OIDC_CLAIM_user_id") &
    headerValueByName("OIDC_CLAIM_expires_in") &
    headerValueByName("OIDC_CLAIM_email")
  ).tmap { case (token, userId, expiresIn, email) => UserInfo(token, SamUserId(userId), SamUserEmail(email), expiresIn.toLong) }

}
