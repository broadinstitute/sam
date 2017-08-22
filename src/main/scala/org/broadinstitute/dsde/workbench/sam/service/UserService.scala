package org.broadinstitute.dsde.workbench.sam.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val allUsersGroupName = SamGroupName("All_Users")

  def createUser(user: SamUser): Future[Option[SamUserStatus]] = {
    for {
      _ <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(user.email.value, toProxyFromUser(user.id.value))
      _ <- googleDirectoryDAO.addUserToGroup(toProxyFromUser(user.id.value), user.email.value)
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
      userStatus <- getUserStatus(user)
    } yield userStatus
  }

  def enableUser(userId: SamUserId, userInfo: UserInfo): Future[Option[SamUserStatus]] = {
    asWorkbenchAdmin(userInfo) {
      directoryDAO.loadUser(userId).flatMap {
        case Some(user) =>
          for {
            _ <- googleDirectoryDAO.addUserToGroup(toProxyFromUser(user.id.value), user.email.value)
            userStatus <- getUserStatus(user)
          } yield userStatus
        case None => Future.successful(None)
      }
    }
  }

  def disableUser(userId: SamUserId, userInfo: UserInfo): Future[Option[SamUserStatus]] = {
    asWorkbenchAdmin(userInfo) {
      directoryDAO.loadUser(userId).flatMap {
        case Some(user) =>
          for {
            _ <- googleDirectoryDAO.removeUserFromGroup(toProxyFromUser(user.id.value), user.email.value)
            userStatus <- getUserStatus(user)
          } yield userStatus
        case None => Future.successful(None)
      }
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

  //TODO: move these to role support in some shared library
  def tryIsWorkbenchAdmin(userEmail: String): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember("fc-admins@dev.test.firecloud.org", userEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) } //TODO
  }

  def asWorkbenchAdmin[T](userInfo: UserInfo)(op: => Future[T]): Future[T] = {
    tryIsWorkbenchAdmin(userInfo.userEmail.value) flatMap { isAdmin =>
      if (isAdmin) op else Future.failed(new WorkbenchException("You must be an admin."))
    }
  }

}
