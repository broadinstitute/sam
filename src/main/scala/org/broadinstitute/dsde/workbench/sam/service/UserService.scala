package org.broadinstitute.dsde.workbench.sam.service

import javax.naming.NameNotFoundException

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val allUsersGroupName = WorkbenchGroupName("All_Users")

  def createUser(user: WorkbenchUser): Future[Option[UserStatus]] = {
    for {
      createdUser <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(user.email.value), WorkbenchGroupEmail(toProxyFromUser(user.id.value))) recover {
        case e:GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
      _ <- directoryDAO.enableUser(user.id)
      _ <- createAllUsersGroup
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)), WorkbenchUserEmail(toProxyFromUser(user.id.value))) //TODO: For now, do a manual add to the All_Users Google group (undo this in Phase II)
      userStatus <- getUserStatus(createdUser.id)
    } yield {
      userStatus
    }
  }

  def getUserStatus(userId: WorkbenchUserId): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          googleStatus <- googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchGroupEmail(user.email.value)) recover { case e: NameNotFoundException => false }
          allUsersStatus <- directoryDAO.isGroupMember(allUsersGroupName, user.id) recover { case e: NameNotFoundException => false }
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
          _ <- directoryDAO.enableUser(user.id)
          _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
          userStatus <- getUserStatus(userId)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def disableUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Option[UserStatus]] = {
    directoryDAO.loadUser(userId).flatMap {
      case Some(user) =>
        for {
          _ <- directoryDAO.disableUser(user.id)
          _ <- googleDirectoryDAO.removeMemberFromGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
          userStatus <- getUserStatus(user.id)
        } yield userStatus
      case None => Future.successful(None)
    }
  }

  def deleteUser(userId: WorkbenchUserId, userInfo: UserInfo): Future[Unit] = {
    for {
      _ <- directoryDAO.removeGroupMember(allUsersGroupName, userId)
      _ <- googleDirectoryDAO.deleteGroup(WorkbenchGroupEmail(toProxyFromUser(userId.value)))
      deleteResult <- directoryDAO.deleteUser(userId)
    } yield deleteResult
  }

  private def createAllUsersGroup: Future[Unit] = {
    for {
      _ <- directoryDAO.createGroup(WorkbenchGroup(allUsersGroupName, Set.empty, WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)))) recover { case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => () }
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(allUsersGroupName.value), WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value))) recover { case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => () }
    } yield ()
  }

  private def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"
  private def toGoogleGroupName(groupName: String): String = s"GROUP_$groupName@$googleDomain"

  //TODO: Move these to RoleSupport.scala (or something) in some shared library
  def tryIsWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(s"fc-admins@$googleDomain"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
  }
}
