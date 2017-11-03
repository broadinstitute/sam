package org.broadinstitute.dsde.workbench.sam.service

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
import scala.util.Failure

/**
  * Created by dvoet on 7/14/17.
  */
object UserService {
  val allUsersGroupName = WorkbenchGroupName("All_Users")
}
class UserService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleIamDAO: GoogleIamDAO, val googleDomain: String, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  import UserService.allUsersGroupName

  def createUser(user: WorkbenchUser): Future[UserStatus] = {
    for {
      createdUser <- directoryDAO.createUser(user)
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(user.email.value), WorkbenchGroupEmail(toProxyFromUser(user.id.value))) recover {
        case e:GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
      _ <- directoryDAO.enableIdentity(user.id)
      _ <- createAllUsersGroup
      _ <- directoryDAO.addGroupMember(allUsersGroupName, user.id)
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)), WorkbenchUserEmail(toProxyFromUser(user.id.value))) //TODO: For now, do a manual add to the All_Users Google group (undo this in Phase II)
      userStatus <- getUserStatus(createdUser.id)
    } yield {
      userStatus.getOrElse(throw new WorkbenchException("getUserStatus returned None after user was created"))
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
          _ <- directoryDAO.enableIdentity(user.id)
          _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
          // Enable the pet service account, if one exists for the user
          _ <- getPetServiceAccountForUser(user.id).flatMap {
            case Some(pet) => enablePetServiceAccount(user.id, pet)
            case None => Future.successful(())
          }
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
          // Disable the pet service account, if one exists for the user
          _ <- getPetServiceAccountForUser(user.id).flatMap {
            case Some(pet) => disablePetServiceAccount(user.id, pet)
            case None => Future.successful(())
          }
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

  def createUserPetServiceAccount(user: WorkbenchUser): Future[WorkbenchUserServiceAccountEmail] = {
    val (petSaID, petSaDisplayName) = toPetSAFromUser(user)

    directoryDAO.getPetServiceAccountForUser(user.id).flatMap {
      case Some(email) => Future.successful(email)
      case None =>
        // First find or create the service account in Google, which generates a unique id and email
        val petSA = googleIamDAO.getOrCreateServiceAccount(petServiceAccountConfig.googleProject, petSaID, petSaDisplayName)
        petSA.flatMap { petServiceAccount =>
          // Set up the service account with the necessary permissions
          setUpServiceAccount(user, petServiceAccount) andThen { case Failure(_) =>
            // If anything fails with setup, clean up any created resources to ensure we don't end up with orphaned pets.
            removePetServiceAccount(user, petServiceAccount).failed.foreach { e =>
              logger.warn(s"Error occurred cleaning up pet service account [$petSaID] [$petSaDisplayName]", e)
            }
        }
      }
    }
  }

  private def enablePetServiceAccount(userId: WorkbenchUserId, petServiceAccount: WorkbenchUserServiceAccount): Future[Unit] = {
    for {
      _ <- directoryDAO.enableIdentity(petServiceAccount.subjectId)
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(userId.value)), petServiceAccount.email)
    } yield ()
  }

  private def disablePetServiceAccount(userId: WorkbenchUserId, petServiceAccount: WorkbenchUserServiceAccount): Future[Unit] = {
    for {
      _ <- directoryDAO.disableIdentity(petServiceAccount.subjectId)
      _ <- googleDirectoryDAO.removeMemberFromGroup(WorkbenchGroupEmail(toProxyFromUser(userId.value)), petServiceAccount.email)
    } yield ()
  }

  private def getPetServiceAccountForUser(userId: WorkbenchUserId): Future[Option[WorkbenchUserServiceAccount]] = {
    directoryDAO.getPetServiceAccountForUser(userId).flatMap {
      case Some(petEmail) => directoryDAO.loadSubjectFromEmail(petEmail.value).map {
        case Some(petId: WorkbenchUserServiceAccountSubjectId) => Some(WorkbenchUserServiceAccount(petId, petEmail, WorkbenchUserServiceAccountDisplayName("")))
        case _ => None
      }
      case None => Future.successful(None)
    }
  }

  private def setUpServiceAccount(user: WorkbenchUser, petServiceAccount: WorkbenchUserServiceAccount): Future[WorkbenchUserServiceAccountEmail] = {
    for {
      // add Service Account User role to the configured emails so they can assume the identity of the pet service account
      _ <- Future.traverse(petServiceAccountConfig.serviceAccountUsers) { email =>
        googleIamDAO.addServiceAccountUserRoleForUser(petServiceAccountConfig.googleProject, petServiceAccount.email, email)
      }
      // add the pet service account attribute to the user's LDAP record
      _ <- directoryDAO.addPetServiceAccountToUser(user.id, petServiceAccount.email)
      // create an additional LDAP record for the pet service account itself (in a different organizational unit than the user)
      _ <- directoryDAO.createPetServiceAccount(petServiceAccount)
      // enable the pet service account
      _ <- enablePetServiceAccount(user.id, petServiceAccount)
    } yield petServiceAccount.email
  }

  private def removePetServiceAccount(user: WorkbenchUser, petServiceAccount: WorkbenchUserServiceAccount): Future[Unit] = {
    for {
      // disable the pet service account
      _ <- disablePetServiceAccount(user.id, petServiceAccount)
      // remove the LDAP record for the pet service account
      _ <- directoryDAO.deletePetServiceAccount(petServiceAccount.subjectId)
      // remove the pet service account attribute on the user's LDAP record
      _ <- directoryDAO.removePetServiceAccountFromUser(user.id)
      // remove the service account itself in Google
      _ <- googleIamDAO.removeServiceAccount(petServiceAccountConfig.googleProject, petServiceAccount.email.toAccountName)
    } yield ()
  }

  def createAllUsersGroup: Future[Unit] = {
    for {
      _ <- directoryDAO.createGroup(WorkbenchGroup(allUsersGroupName, Set.empty, WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value)))) recover { case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => () }
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName(allUsersGroupName.value), WorkbenchGroupEmail(toGoogleGroupName(allUsersGroupName.value))) recover { case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => () }
    } yield ()
  }

  def getUserFromPetServiceAccount(petSAEmail:WorkbenchUserEmail):Future[Option[WorkbenchUser]] = {
    directoryDAO.getUserFromPetServiceAccount(WorkbenchUserServiceAccountEmail(petSAEmail.value))
  }

  private[service] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"
  private[service] def toGoogleGroupName(groupName: String): String = s"GROUP_$groupName@$googleDomain"

  private[service] def toPetSAFromUser(user: WorkbenchUser): (WorkbenchUserServiceAccountName, WorkbenchUserServiceAccountDisplayName) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     * Subject IDs are 22 numeric characters, so "pet-${subjectId}" fulfills these requirements.
     */
    val serviceAccountName = s"pet-${user.id.value}"
    val displayName = s"Pet Service Account for user [${user.email.value}]"

    (WorkbenchUserServiceAccountName(serviceAccountName), WorkbenchUserServiceAccountDisplayName(displayName))
  }

  //TODO: Move these to RoleSupport.scala (or something) in some shared library
  def tryIsWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(s"fc-admins@$googleDomain"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
  }
}
