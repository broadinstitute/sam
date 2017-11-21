package org.broadinstitute.dsde.workbench.sam.service

import javax.naming.NameNotFoundException

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createUser(user: WorkbenchUser): Future[UserStatus] = {
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
      createdUser <- directoryDAO.createUser(user)
      _ <- directoryDAO.enableIdentity(user.id)
      _ <- directoryDAO.addGroupMember(allUsersGroup.id, user.id)
      _ <- cloudExtensions.onUserCreate(createdUser)
      userStatus <- getUserStatus(createdUser.id)
    } yield {
      userStatus.getOrElse(throw new WorkbenchException("getUserStatus returned None after user was created"))
    }
  }

  def getUserStatus(userId: WorkbenchUserId): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          googleStatus <- cloudExtensions.getUserStatus(user)
          allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
          allUsersStatus <- directoryDAO.isGroupMember(allUsersGroup.id, user.id) recover { case e: NameNotFoundException => false }
          ldapStatus <- directoryDAO.isEnabled(user.id)
        } yield {
          Option(UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> ldapStatus, "allUsersGroup" -> allUsersStatus, "google" -> googleStatus)))
        }

      case None => Future.successful(None)
    }
  }

  def enableUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.enableIdentity(user.id)
          _ <- cloudExtensions.onUserEnable(user)
          userStatus <- getUserStatus(userId)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def disableUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.disableIdentity(user.id)
          _ <- cloudExtensions.onUserDisable(user)
          userStatus <- getUserStatus(user.id)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def deleteUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Unit] = {
    for {
      allUsersGroup <- cloudExtensions.getOrCreateAllUsersGroup(directoryDAO)
      _ <- directoryDAO.removeGroupMember(allUsersGroup.id, userId)
      _ <- cloudExtensions.onUserDelete(userId)
      deleteResult <- directoryDAO.deleteUser(userId)
    } yield deleteResult
  }

  private[service] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@${cloudExtensions.emailDomain}"
}
