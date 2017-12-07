package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess}
import org.broadinstitute.dsde.workbench.model._
import akka.http.scaladsl.server.Directives.headerValueByName
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.UserInfo

import scala.concurrent.{ExecutionContext, Future}



trait StandardUserInfoDirectives extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext

  val petSAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isPetSA(email: WorkbenchEmail) = {
    petSAdomain.pattern.matcher(email.value).matches
  }

  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
      headerValueByName("OIDC_CLAIM_user_id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email")
    ) tflatMap {
    case (token, userId, expiresIn, email) => {
      val userInfo = UserInfo(token, WorkbenchUserId(userId), WorkbenchEmail(email), expiresIn.toLong)
      onSuccess(getUserFromPetServiceAccount(userInfo).map {
        case Some(petOwnerUser) => UserInfo(token, petOwnerUser.id, petOwnerUser.email, expiresIn.toLong)
        case None => userInfo
      })
    }
  }

  private def getUserFromPetServiceAccount(userInfo: UserInfo):Future[Option[WorkbenchUser]] = {
    if (isPetSA(userInfo.userEmail)) {
      directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(userInfo.userId.value))
    } else {
      Future.successful(None)
    }
  }
}
