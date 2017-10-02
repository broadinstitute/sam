package org.broadinstitute.dsde.workbench.sam.service

import java.nio.ByteBuffer
import java.util.UUID
import javax.naming.NameNotFoundException

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.config.PetServiceAccountConfig

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
object UserService {
  val allUsersGroupName = WorkbenchGroupName("All_Users")
}
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleIamDAO: GoogleIamDAO, val googleDomain: String, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  import UserService.allUsersGroupName

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

  def createUserPetServiceAccount(user: WorkbenchUser): Future[WorkbenchUserPetServiceAccountEmail] = {
    def create() = {
      val (petSa, petSaDisplayName) = toPetSAFromUser(user)
      for {
        petServiceAccount <- googleIamDAO.createServiceAccount(petServiceAccountConfig.googleProject, petSa, petSaDisplayName)
        _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), petServiceAccount.email)
        _ <- Future.traverse(petServiceAccountConfig.serviceAccountActors) { email =>
          googleIamDAO.addServiceAccountActorRoleForUser(petServiceAccountConfig.googleProject, petServiceAccount.email, email)
        }
        _ <- directoryDAO.addPetServiceAccountToUser(user.id, petServiceAccount.email)
      } yield petServiceAccount.email
    }

    directoryDAO.getPetServiceAccountForUser(user.id).flatMap {
      case Some(email) => Future.successful(email)
      case None => create()
    }
  }

  def createAllUsersGroup: Future[Unit] = {
    for {
      _ <- directoryDAO.createGroup(WorkbenchGroup(allUsersGroupName, Set.empty, WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)))) recover { case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => () }
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(allUsersGroupName.value), WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value))) recover { case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => () }
    } yield ()
  }

  private[service] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"
  private[service] def toGoogleGroupName(groupName: String): String = s"GROUP_$groupName@$googleDomain"

  private[service] def toPetSAFromUser(user: WorkbenchUser): (WorkbenchUserPetServiceAccountId, WorkbenchUserPetServiceAccountDisplayName) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     * UUID.randomUUID() is too many characters, so generate a random 8 byte UUID prefixed with "pet-".
     */
    val randLong = ByteBuffer.wrap(UUID.randomUUID().toString.getBytes()).getLong
    val serviceAccountId = s"pet-${java.lang.Long.toString(randLong, Character.MAX_RADIX)}"
    val displayName = s"Pet Service Account for user [${user.email.value}]"

    (WorkbenchUserPetServiceAccountId(serviceAccountId), WorkbenchUserPetServiceAccountDisplayName(displayName))
  }

  //TODO: Move these to RoleSupport.scala (or something) in some shared library
  def tryIsWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(s"fc-admins@$googleDomain"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
  }
}
