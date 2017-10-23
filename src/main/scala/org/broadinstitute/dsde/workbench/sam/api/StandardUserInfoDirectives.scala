package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{WorkbenchUser, WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

trait StandardUserInfoDirectives extends UserInfoDirectives {
  // TODO inject this into StandardUserInfoDirectives somehow
  val dao: DirectoryDAO

  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
    headerValueByName("OIDC_CLAIM_user_id") &
    headerValueByName("OIDC_CLAIM_expires_in") &
    headerValueByName("OIDC_CLAIM_email")
  ).tmap { case (token, userId, expiresIn, email) => UserInfo(token, WorkbenchUserId(userId), WorkbenchUserEmail(email), expiresIn.toLong) }

  def requireUserInfoWithPets: Directive1[UserInfo] = {
    requireUserInfo.flatMap { userInfo =>
      if (userInfo.userEmail.isServiceAccount) {
        val petOwner = WorkbenchUserId("todo") // TODO strip out pet-<userid>@gserviceaccount.com the right way
        onSuccess(dao.loadUser(petOwner)).flatMap {
          case Some(user: WorkbenchUser) =>
            provide(userInfo.copy(userId = user.id, userEmail = user.email))
          case _ =>
            // owning user wasn't found; continue with the original credentials
            provide(userInfo)
        }
      } else provide(userInfo)
    }
  }
}
