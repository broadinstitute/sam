package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{headerValueByName, optionalHeaderValueByName}
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

trait StandardUserInfoDirectives extends UserInfoDirectives {

  // TODO - I added OIDC_CLAIM_user_id here to show an example of how we would compose directives, I am not sure it is right, thus not used
  def requireUserInfo: Directive1[UserInfo] = (headerValueByName("iPlanetDirectoryPro") & optionalHeaderValueByName("OIDC_CLAIM_user_id")).tmap { case (token, userId) => UserInfo(token) }

}
