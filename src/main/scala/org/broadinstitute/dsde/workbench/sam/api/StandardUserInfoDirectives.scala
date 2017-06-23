package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.headerValueByName
import org.broadinstitute.dsde.workbench.sam.model.{SamUserId, UserInfo}

trait StandardUserInfoDirectives extends UserInfoDirectives {

  def requireUserInfo: Directive1[UserInfo] = (headerValueByName("iPlanetDirectoryPro") & headerValueByName("OIDC_CLAIM_user_id")).tmap { case (token, userId) => UserInfo(token, SamUserId(userId)) }

}
