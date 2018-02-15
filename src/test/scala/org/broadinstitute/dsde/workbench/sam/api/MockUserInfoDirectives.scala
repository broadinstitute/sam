package org.broadinstitute.dsde.workbench.sam.api
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.provide
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}

/**
  * Created by dvoet on 6/7/17.
  */
trait MockUserInfoDirectives extends UserInfoDirectives {
  val userInfo: UserInfo
  val petSAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isPetSA(email: String) = {
    petSAdomain.pattern.matcher(email).matches
  }
  override def requireUserInfo: Directive1[UserInfo] = provide(if(isPetSA(userInfo.userEmail.value)) {
    new UserInfo(OAuth2BearerToken(""),WorkbenchUserId("newuser"), WorkbenchEmail("newuser@new.com"), userInfo.tokenExpiresIn)
  } else
    userInfo
  )
}
