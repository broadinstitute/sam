package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Created by dvoet on 6/7/17.
  */
trait MockUserInfoDirectives extends UserInfoDirectives {
  val user: SamUser
  val workbenchUser: Option[SamUser] = None

  val petSAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r

  private def isPetSA(email: String) = {
    petSAdomain.pattern.matcher(email).matches
  }

  override def requireUserInfo(samRequestContext: SamRequestContext): Directive1[SamUser] = onSuccess {
    directoryDAO.loadSubjectFromEmail(user.email, samRequestContext).map { maybeUser =>
      maybeUser.map { _ =>
        if (isPetSA(user.email.value)) {
          user.copy(id = WorkbenchUserId("newuser"), email = WorkbenchEmail("newuser@new.com"))
        } else {
          user
        }
        // forbidden status code matches what the StandardUserInfoDirectives does when user is not found
      }.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "user not found")))
    }.unsafeToFuture()
  }

  override def requireCreateUser(samRequestContext: SamRequestContext): Directive1[SamUser] = workbenchUser match {
    case None => failWith(new Exception("workbenchUser not specified"))
    case Some(u) => provide(u)
  }
}
