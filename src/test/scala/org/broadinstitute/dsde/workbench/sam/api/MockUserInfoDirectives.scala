package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Created by dvoet on 6/7/17.
  */
trait MockUserInfoDirectives extends UserInfoDirectives {
  val userInfo: UserInfo
  val workbenchUser: Option[WorkbenchUser] = None

  val petSAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isPetSA(email: String) = {
    petSAdomain.pattern.matcher(email).matches
  }
  override def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo] = provide(if(isPetSA(userInfo.userEmail.value)) {
    new UserInfo(OAuth2BearerToken(""),WorkbenchUserId("newuser"), WorkbenchEmail("newuser@new.com"), userInfo.tokenExpiresIn)
  } else
    userInfo
  )

  override def requireCreateUser(samRequestContext: SamRequestContext): Directive1[WorkbenchUser] = workbenchUser match {
    case None => failWith(new Exception("workbenchUser not specified"))
    case Some(u) => provide(u)
  }
}
