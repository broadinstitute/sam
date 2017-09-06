package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.{WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  private val allUsersGroupName = SamGroupName("All_Users")

  def createUser(user: SamUser): Future[Option[SamUserStatus]] = {
    for {
      _ <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(user.email.value), WorkbenchGroupEmail(toProxyFromUser(user.id.value)))
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
      _ <- directoryDAO.enableUser(user.id)
      _ <- createAllUsersGroup
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)), WorkbenchUserEmail(toProxyFromUser(user.id.value))) //TODO: For now, do a manual add to the All_Users Google group (undo this in Phase II)
      userStatus <- getUserStatus(user)
    } yield {
      userStatus
    }
  }

  def enableUser(userId: SamUserId, userInfo: UserInfo): Future[Option[SamUserStatus]] = {
    asWorkbenchAdmin(userInfo) {
      directoryDAO.loadUser(userId).flatMap {
        case Some(user) =>
          for {
            _ <- directoryDAO.enableUser(user.id)
            _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
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
            _ <- directoryDAO.disableUser(user.id)
            _ <- googleDirectoryDAO.removeMemberFromGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchGroupEmail(user.email.value))
            userStatus <- getUserStatus(user)
          } yield userStatus
        case None => Future.successful(None)
      }
    }
  }

  def getUserStatus(user: SamUser): Future[Option[SamUserStatus]] = {
    for {
      loadedUser <- directoryDAO.loadUser(user.id)
      googleStatus <- googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchGroupEmail(user.email.value))
      allUsersStatus <- directoryDAO.isGroupMember(allUsersGroupName, user.id)
      ldapStatus <- directoryDAO.isEnabled(user.id)
    } yield {
      loadedUser.map { user =>
        Option(SamUserStatus(user, Map("ldap" -> ldapStatus, "allUsersGroup" -> allUsersStatus, "google" -> googleStatus)))
      }.getOrElse(None)
    }
  }

  private def createAllUsersGroup: Future[Unit] = {
    directoryDAO.createGroup(SamGroup(allUsersGroupName, Set.empty, SamGroupEmail(toGoogleGroupName(allUsersGroupName.value)))) flatMap { _ =>
      googleDirectoryDAO.createGroup(WorkbenchGroupName(allUsersGroupName.value), WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)))
    } recover {
      case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => ()
    }
  }

  private def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"
  private def toGoogleGroupName(groupName: String): String = s"GROUP_$groupName@$googleDomain"

  //TODO: Move these to RoleSupport.scala (or something) in some shared library
  def tryIsWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(s"fc-admins@$googleDomain"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
  }

  def asWorkbenchAdmin[T](userInfo: UserInfo)(op: => Future[T]): Future[T] = {
    tryIsWorkbenchAdmin(WorkbenchUserEmail(userInfo.userEmail.value)) flatMap { isAdmin =>
      if (isAdmin) op else Future.failed(new WorkbenchException("You must be an admin."))
    }
  }

}
