package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future



trait StandardUserInfoDirectives extends UserInfoDirectives {
  val petSAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isPetSA(email: String) = {
    petSAdomain.pattern.matcher(email).matches
  }

  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
      headerValueByName("OIDC_CLAIM_user_id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email")
    ) tflatMap {
    case (token, userId, expiresIn, email) => {
      onSuccess(getUserFromPetServiceAccount(email)).map {
        case Some(petOwnerUser) => UserInfo(token, petOwnerUser.id, petOwnerUser.email, expiresIn.toLong)
        case None => UserInfo(token, WorkbenchUserId(userId), WorkbenchUserEmail(email), expiresIn.toLong)
      }
    }
  }

  private def getUserFromPetServiceAccount(email:String):Future[Option[WorkbenchUser]] = {
    if (isPetSA(email))
      directoryDAO.getUserFromPetServiceAccount(WorkbenchUserServiceAccountEmail(email))
    else
      Future(None)
  }
}
