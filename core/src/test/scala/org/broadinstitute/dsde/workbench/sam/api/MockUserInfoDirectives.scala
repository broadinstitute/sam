package org.broadinstitute.dsde.workbench.sam.api
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.provide
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

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
    new UserInfo("",WorkbenchUserId("newuser"), WorkbenchEmail("newuser@new.com"), userInfo.tokenExpiresIn)
  } else
    userInfo
  )
}
