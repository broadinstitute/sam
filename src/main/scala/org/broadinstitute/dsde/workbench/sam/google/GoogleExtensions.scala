package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.ResourceAndPolicyName
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.util.FutureSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class GoogleExtensions(val directoryDAO: DirectoryDAO, val accessPolicyDAO: AccessPolicyDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googlePubSubDAO: GooglePubSubDAO, val googleIamDAO: GoogleIamDAO, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging with FutureSupport with CloudExtensions {
  private[google] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@${googleServicesConfig.appsDomain}"

  override def onBoot()(implicit system: ActorSystem): Unit = {
    system.actorOf(GoogleGroupSyncMonitor.props(
      googleServicesConfig.groupSyncPollInterval,
      googleServicesConfig.groupSyncPollJitter,
      googlePubSubDAO,
      googleServicesConfig.groupSyncSubscription,
      this
    ))
  }

  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit] = {
    import spray.json._
    import WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
    import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.ResourceAndPolicyNameFormat

    for {
      idsAndSyncDates <- Future.traverse(groupIdentities) { id => directoryDAO.getSynchronizedDate(id).map(dateOption => id -> dateOption) }
      // only sync groups that have already been synchronized
      messagesForIdsWithSyncDates = idsAndSyncDates.collect {
        case (gn: WorkbenchGroupName, Some(_)) => gn.toJson.compactPrint
        case (rpn: ResourceAndPolicyName, Some(_)) => rpn.toJson.compactPrint
      }
      _ <- googlePubSubDAO.publishMessages(googleServicesConfig.groupSyncTopic, messagesForIdsWithSyncDates)
    } yield ()
  }

  override def onUserCreate(user: WorkbenchUser): Future[Unit] = {
    for {
      _ <- googleDirectoryDAO.createGroup(user.email.value, WorkbenchGroupEmail(toProxyFromUser(user.id.value))) recover {
        case e:GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
    } yield ()
  }

  override def getUserStatus(user: WorkbenchUser): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchGroupEmail(user.email.value))
  }

  override def onUserEnable(user: WorkbenchUser): Future[Unit] = {
    for {
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
      // Enable the pet service account, if one exists for the user
      _ <- getPetServiceAccountForUser(user.id).flatMap {
        case Some(pet) => enablePetServiceAccount(user.id, pet)
        case None => Future.successful(())
      }
    } yield ()
  }

  override def onUserDisable(user: WorkbenchUser): Future[Unit] = {
    for {
    // Disable the pet service account, if one exists for the user
      _ <- getPetServiceAccountForUser(user.id).flatMap {
        case Some(pet) => disablePetServiceAccount(user.id, pet)
        case None => Future.successful(())
      }
      _ <- googleDirectoryDAO.removeMemberFromGroup(WorkbenchGroupEmail(toProxyFromUser(user.id.value)), WorkbenchUserEmail(user.email.value))
    } yield ()
  }

  override def onUserDelete(userId: WorkbenchUserId): Future[Unit] = {
    for {
      _ <- googleDirectoryDAO.deleteGroup(WorkbenchGroupEmail(toProxyFromUser(userId.value)))
      _ <- getPetServiceAccountForUser(userId).flatMap {
        case Some(pet) => googleIamDAO.removeServiceAccount(petServiceAccountConfig.googleProject, pet.email.toAccountName)
        case None => Future.successful(())
      }
    } yield ()
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

  def synchronizeGroupMembers(groupId: WorkbenchGroupIdentity): Future[SyncReport] = {
    def toSyncReportItem(operation: String, email: String, result: Try[Unit]) = {
      SyncReportItem(
        operation,
        email,
        result match {
          case Success(_) => None
          case Failure(t) => Option(ErrorReport(t))
        }
      )
    }

    for {
      groupOption <- groupId match {
        case basicGroupName: WorkbenchGroupName => directoryDAO.loadGroup(basicGroupName)
        case rpn: ResourceAndPolicyName => accessPolicyDAO.loadPolicy(rpn)
      }

      group = groupOption.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))

      googleMemberEmails <- googleDirectoryDAO.listGroupMembers(group.email) flatMap {
        case None => googleDirectoryDAO.createGroup(groupId.toString, group.email) map (_ => Set.empty[String])
        case Some(members) => Future.successful(members.toSet)
      }

      samMemberEmails <- Future.traverse(group.members) {
        case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group)

        // use proxy group email instead of user's actual email
        case WorkbenchUserId(userSubjectId) => Future.successful(Option(WorkbenchUserEmail(toProxyFromUser(userSubjectId))))

        // not sure why this next case would happen but if a petSA is in a group just use its email
        case petSA: WorkbenchUserServiceAccountSubjectId => directoryDAO.loadSubjectEmail(petSA)
      }.map(_.collect { case Some(email) => email.value })

      toAdd = samMemberEmails -- googleMemberEmails
      toRemove = googleMemberEmails -- samMemberEmails

      addTrials <- Future.traverse(toAdd) { addEmail => googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchUserEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _)) }
      removeTrials <- Future.traverse(toRemove) { removeEmail => googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchUserEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _)) }

      _ <- directoryDAO.updateSynchronizedDate(groupId)
    } yield {
      SyncReport(group.email, Seq(addTrials, removeTrials).flatten)
    }
  }

  private[google] def toPetSAFromUser(user: WorkbenchUser): (WorkbenchUserServiceAccountName, WorkbenchUserServiceAccountDisplayName) = {
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

}
