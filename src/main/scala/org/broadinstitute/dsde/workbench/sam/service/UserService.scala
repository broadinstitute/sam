package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.{WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val allUsersGroupName = SamGroupName("All_Users")

  def createUser(user: SamUser): Future[SamUser] = {
    for {
      _ <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(user.email.value, toProxyFromUser(user.id.value))
      _ <- googleDirectoryDAO.addUserToGroup(toProxyFromUser(user.id.value), user.email.value)
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
    } yield user
  }

  //TODO: admin only
  def enableUser(userId: SamUserId): Future[Option[SamUserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- googleDirectoryDAO.addUserToGroup(toProxyFromUser(user.id.value), user.email.value)
          userStatus <- getUserStatus(user)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  //TODO: admin only
  def disableUser(userId: SamUserId): Future[Option[SamUserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- googleDirectoryDAO.removeUserFromGroup(toProxyFromUser(user.id.value), user.email.value)
          userStatus <- getUserStatus(user)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def getUserStatus(user: SamUser): Future[Option[SamUserStatus]] = {
    for {
      loadedUser <- directoryDAO.loadUser(user.id)
      googleStatus <- googleDirectoryDAO.isGroupMember(toProxyFromUser(user.id.value), user.email.value)
      allUsersStatus <- directoryDAO.isGroupMember(allUsersGroupName, user.id)
    } yield {
      loadedUser.map { user =>
        Option(SamUserStatus(user, Map("google" -> googleStatus, "allUsersGroup" -> allUsersStatus)))
      }.getOrElse(None)
    }
  }

  private def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"

}
