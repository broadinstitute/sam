package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.UserInfo
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future



trait StandardUserInfoDirectives extends UserInfoDirectives {
  val petSAdomain = "\\S+@\\S+.iam.gserviceaccount.com"

  def requireUserInfo: Directive1[UserInfo] = (
    headerValueByName("OIDC_access_token") &
    headerValueByName("OIDC_CLAIM_user_id") &
    headerValueByName("OIDC_CLAIM_expires_in") &
    headerValueByName("OIDC_CLAIM_email")
  ) tflatMap {
    case (token, userId, expiresIn, email) => {
      onSuccess(getWorkbenchUserEmailId(email)).map {
        case Some(resourceType) => UserInfo(token, resourceType.id, resourceType.email, expiresIn.toLong)
        case None => UserInfo(token, WorkbenchUserId(userId), WorkbenchUserEmail(email), expiresIn.toLong)
      }
    }
  }

  private final val logger = LoggerFactory.getLogger(classOf[StandardUserInfoDirectives])

  private def isPetSA(email:String) = email.matches(petSAdomain)

  private def getWorkbenchUserEmailId(email:String):Future[Option[WorkbenchUser]] = {
    //    if (isPetSA(email))
    //      directoryDAO.getUserFromPetServiceAccount(WorkbenchUserServiceAccountEmail(email))
    //      else
    //      Future(None)
    //  }

    if(isPetSA(email)) {
      logger.info(s"Email $email is a pet service account. Looking up user...")
      val future  = directoryDAO.getUserFromPetServiceAccount(WorkbenchUserServiceAccountEmail(email))
      future.recover { case e =>
        logger.error(s"Error occurred looking up user from pet service account $email", e)
        throw e
      }
    }
    else
      Future(None)
  }
}
