package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.{headerValueByName, optionalHeaderValueByName, onSuccess}
import org.broadinstitute.dsde.workbench.model._
import akka.http.scaladsl.server.Directives.headerValueByName
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}

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
      optionalHeaderValueByName("X-google-subject-id") &
      headerValueByName("OIDC_CLAIM_expires_in") &
      headerValueByName("OIDC_CLAIM_email")
    ) tflatMap {
    case (token, userId, googleSubjectId, expiresIn, email) => {
      println(s"11111: $googleSubjectId")
      val userInfo = UserInfo(OAuth2BearerToken(token), WorkbenchUserId(userId), WorkbenchEmail(email), expiresIn.toLong)
      onSuccess(getUserFromPetServiceAccount(userInfo).map {
        case Some(petOwnerUser) => UserInfo(OAuth2BearerToken(token), petOwnerUser.id, petOwnerUser.email, expiresIn.toLong)
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
