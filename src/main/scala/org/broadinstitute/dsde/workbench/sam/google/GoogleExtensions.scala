package org.broadinstitute.dsde.workbench.sam.google

import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.model.GoogleProject
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.util.FutureSupport
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, SubsystemStatus, Subsystems}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class GoogleExtensions(val directoryDAO: DirectoryDAO, val accessPolicyDAO: AccessPolicyDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googlePubSubDAO: GooglePubSubDAO, val googleIamDAO: GoogleIamDAO, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging with FutureSupport with CloudExtensions {
  private[google] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@${googleServicesConfig.appsDomain}"

  override val emailDomain = googleServicesConfig.appsDomain
  private val allUsersGroupEmail = WorkbenchGroupEmail(s"GROUP_${allUsersGroupName.value}@$emailDomain")

  override def getOrCreateAllUsersGroup(directoryDAO: DirectoryDAO)(implicit executionContext: ExecutionContext): Future[WorkbenchGroup] = {
    val allUsersGroup = BasicWorkbenchGroup(allUsersGroupName, Set.empty, allUsersGroupEmail)
    for {
      createdGroup <- directoryDAO.createGroup(allUsersGroup) recover {
        case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => allUsersGroup
      }
      _ <- googleDirectoryDAO.createGroup(createdGroup.id.toString, createdGroup.email) recover { case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => () }
    } yield createdGroup
  }

  override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(WorkbenchGroupEmail(s"fc-admins@${googleServicesConfig.appsDomain}"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
  }

  override def onBoot()(implicit system: ActorSystem): Unit = {
    system.actorOf(GoogleGroupSyncMonitorSupervisor.props(
      googleServicesConfig.groupSyncPollInterval,
      googleServicesConfig.groupSyncPollJitter,
      googlePubSubDAO,
      googleServicesConfig.groupSyncTopic,
      googleServicesConfig.groupSyncSubscription,
      googleServicesConfig.groupSyncWorkerCount,
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

      allUsersGroup <- getOrCreateAllUsersGroup(directoryDAO)

      _ <- googleDirectoryDAO.addMemberToGroup(allUsersGroup.email, WorkbenchUserEmail(toProxyFromUser(user.id.value)))
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

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = {
    directoryDAO.getSynchronizedDate(groupId)
  }

  def synchronizeGroupMembers(groupId: WorkbenchGroupIdentity, visitedGroups: Set[WorkbenchGroupIdentity] = Set.empty[WorkbenchGroupIdentity]): Future[Map[WorkbenchGroupEmail, Seq[SyncReportItem]]] = {
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
    if(visitedGroups.contains(groupId)) {
      Future.successful(Map.empty)
    } else {
      for {
        groupOption <- groupId match {
          case basicGroupName: WorkbenchGroupName => directoryDAO.loadGroup(basicGroupName)
          case rpn: ResourceAndPolicyName => accessPolicyDAO.loadPolicy(rpn)
        }

        group = groupOption.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))

        subGroupSyncs <- Future.traverse(group.members) {
          case subGroup: WorkbenchGroupIdentity =>
            directoryDAO.getSynchronizedDate(subGroup).flatMap{
              case None => synchronizeGroupMembers(subGroup, visitedGroups + groupId)
              case _ => Future.successful(Map.empty[WorkbenchGroupEmail, Seq[SyncReportItem]])
            }
          case _ => Future.successful(Map.empty[WorkbenchGroupEmail, Seq[SyncReportItem]])
        }

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
        Map(group.email -> Seq(addTrials, removeTrials).flatten) ++ subGroupSyncs.flatten
      }
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

  override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = {
    import HealthMonitor._

    def checkGroups: Future[SubsystemStatus] = {
      logger.debug("Checking Google Groups...")
      for {
        groupOption <- googleDirectoryDAO.getGoogleGroup(allUsersGroupEmail)
      } yield {
        groupOption match {
          case Some(_) => OkStatus
          case None => failedStatus(s"could not find group ${allUsersGroupEmail} in google")
        }
      }
    }

    def checkPubsub: Future[SubsystemStatus] = {
      logger.debug("Checking Google PubSub...")
      googlePubSubDAO.getTopic(googleServicesConfig.groupSyncTopic).map {
        case Some(_) => OkStatus
        case None => failedStatus(s"Could not find topic: ${googleServicesConfig.groupSyncTopic}")
      }
    }

    def checkIam: Future[SubsystemStatus] = {
      val accountName = WorkbenchUserServiceAccountEmail(googleServicesConfig.serviceAccountClientEmail).toAccountName
      googleIamDAO.findServiceAccount(GoogleProject(googleServicesConfig.serviceAccountClientProject), accountName).map {
        case Some(_) => OkStatus
        case None => failedStatus(s"Could not find service account: $accountName")
      }
    }


    Map(
      Subsystems.GoogleGroups -> checkGroups,
      Subsystems.GooglePubSub -> checkPubsub,
      Subsystems.GoogleIam -> checkIam
    )
  }

  override val allSubSystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.GooglePubSub, Subsystems.GoogleIam)
}
