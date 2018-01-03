package org.broadinstitute.dsde.workbench.sam.google

import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
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
  private[google] def toProxyFromUser(subjectId: WorkbenchUserId): WorkbenchEmail = WorkbenchEmail(s"PROXY_${subjectId.value}@${googleServicesConfig.appsDomain}")

  override val emailDomain = googleServicesConfig.appsDomain
  private val allUsersGroupEmail = WorkbenchEmail(s"GROUP_${allUsersGroupName.value}@$emailDomain")

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
    googleDirectoryDAO.isGroupMember(WorkbenchEmail(s"fc-admins@${googleServicesConfig.appsDomain}"), memberEmail) recoverWith { case t => throw new WorkbenchException("Unable to query for admin status.", t) }
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
    import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.ResourceAndPolicyNameFormat
    import WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
    import spray.json._

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
      _ <- googleDirectoryDAO.createGroup(user.email.value, toProxyFromUser(user.id)) recover {
        case e:GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      _ <- googleDirectoryDAO.addMemberToGroup(toProxyFromUser(user.id), WorkbenchEmail(user.email.value))

      allUsersGroup <- getOrCreateAllUsersGroup(directoryDAO)

      _ <- googleDirectoryDAO.addMemberToGroup(allUsersGroup.email, toProxyFromUser(user.id))
    } yield ()
  }

  override def getUserStatus(user: WorkbenchUser): Future[Boolean] = {
    googleDirectoryDAO.isGroupMember(toProxyFromUser(user.id), WorkbenchEmail(user.email.value))
  }

  override def onUserEnable(user: WorkbenchUser): Future[Unit] = {
    for {
      _ <- googleDirectoryDAO.addMemberToGroup(toProxyFromUser(user.id), WorkbenchEmail(user.email.value))
      // Enable the pet service account, if one exists for the user
      _ <- directoryDAO.getAllPetServiceAccountsForUser(user.id).flatMap { Future.traverse(_) { pet =>
        enablePetServiceAccount(pet)
      } }
    } yield ()
  }

  override def onUserDisable(user: WorkbenchUser): Future[Unit] = {
    for {
    // Disable the pet service account, if one exists for the user
      _ <- directoryDAO.getAllPetServiceAccountsForUser(user.id).flatMap { Future.traverse(_) { pet =>
        disablePetServiceAccount(pet)
      } }
      _ <- googleDirectoryDAO.removeMemberFromGroup(toProxyFromUser(user.id), WorkbenchEmail(user.email.value))
    } yield ()
  }

  override def onUserDelete(userId: WorkbenchUserId): Future[Unit] = {
    for {
      _ <- googleDirectoryDAO.deleteGroup(toProxyFromUser(userId))
      _ <- directoryDAO.getAllPetServiceAccountsForUser(userId).flatMap { Future.traverse(_) { pet =>
        googleIamDAO.removeServiceAccount(petServiceAccountConfig.googleProject, toAccountName(pet.serviceAccount.email))
      } }
      pets <- directoryDAO.getAllPetServiceAccountsForUser(userId)
      _ <- Future.traverse(pets) { pet => directoryDAO.deletePetServiceAccount(pet.id) }
    } yield ()
  }

  @deprecated("Use new two-argument version of this function", "Sam Phase 3")
  def createUserPetServiceAccount(user: WorkbenchUser): Future[PetServiceAccount] = createUserPetServiceAccount(user, petServiceAccountConfig.googleProject)

  @deprecated("Use new two-argument version of this function", "Sam Phase 3")
  def deleteUserPetServiceAccount(userId: WorkbenchUserId): Future[Boolean] = deleteUserPetServiceAccount(userId, petServiceAccountConfig.googleProject)

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject): Future[Boolean] = {
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      deletedSomething <- maybePet match {
        case Some(pet) =>
          for {
            _ <- directoryDAO.deletePetServiceAccount(PetServiceAccountId(userId, project))
            _ <- googleIamDAO.removeServiceAccount(project, toAccountName(pet.serviceAccount.email))
          } yield true

        case  None => Future.successful(false) // didn't find the pet, nothing to delete
      }
    } yield deletedSomething
  }

  def createUserPetServiceAccount(user: WorkbenchUser, project: GoogleProject): Future[PetServiceAccount] = {
    val (petSaID, petSaDisplayName) = toPetSAFromUser(user)

    directoryDAO.loadPetServiceAccount(PetServiceAccountId(user.id, project)).flatMap {
      case Some(pet) => Future.successful(pet)
      case None =>
        // First find or create the service account in Google, which generates a unique id and email
        val petSA = googleIamDAO.getOrCreateServiceAccount(project, petSaID, petSaDisplayName)
        petSA.flatMap { serviceAccount =>
          // Set up the service account with the necessary permissions
          val petServiceAccount = PetServiceAccount(PetServiceAccountId(user.id, project), serviceAccount)
          setUpServiceAccount(petServiceAccount) andThen { case Failure(_) =>
            // If anything fails with setup, clean up any created resources to ensure we don't end up with orphaned pets.
            removePetServiceAccount(petServiceAccount).failed.foreach { e =>
              logger.warn(s"Error occurred cleaning up pet service account [$petSaID] [$petSaDisplayName]", e)
            }
          }
        }
    }
  }

  private def enablePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] = {
    for {
      _ <- directoryDAO.enableIdentity(petServiceAccount.id)
      _ <- googleDirectoryDAO.addMemberToGroup(toProxyFromUser(petServiceAccount.id.userId), petServiceAccount.serviceAccount.email)
    } yield ()
  }

  private def disablePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] = {
    for {
      _ <- directoryDAO.disableIdentity(petServiceAccount.id)
      _ <- googleDirectoryDAO.removeMemberFromGroup(toProxyFromUser(petServiceAccount.id.userId), petServiceAccount.serviceAccount.email)
    } yield ()
  }

  private def getPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = {
    directoryDAO.getAllPetServiceAccountsForUser(userId)
  }

  private def setUpServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = {
    for {
      // create an additional LDAP record for the pet service account itself (in a different organizational unit than the user)
      _ <- directoryDAO.createPetServiceAccount(petServiceAccount)
      // enable the pet service account
      _ <- enablePetServiceAccount(petServiceAccount)
    } yield petServiceAccount
  }

  private def removePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] = {
    for {
    // disable the pet service account
      _ <- disablePetServiceAccount(petServiceAccount)
      // remove the LDAP record for the pet service account
      _ <- directoryDAO.deletePetServiceAccount(petServiceAccount.id)
      // remove the service account itself in Google
      _ <- googleIamDAO.removeServiceAccount(petServiceAccountConfig.googleProject, toAccountName(petServiceAccount.serviceAccount.email))
    } yield ()
  }


  //this class is getting to be too big. look into moving some things out of it.
  def createPetServiceAccountKey(userId: WorkbenchUserId, project: GoogleProject): Future[Unit] = {
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      response <- maybePet match {
        case Some(pet) =>
          for {
            _ <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
              case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
            }
          } yield ()

        case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Pet service account not found. Please create one and try again."))
      }
    } yield response
  }

  def removePetServiceAccountKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = {
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      response <- maybePet match {
        case Some(pet) =>
          for {
            _ <- googleIamDAO.removeServiceAccountKey(project, pet.serviceAccount.email, keyId)
          } yield ()

        case None => Future.successful(()) // didn't find the pet, nothing to remove
      }
    } yield response
  }

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = {
    directoryDAO.getSynchronizedDate(groupId)
  }

  def synchronizeGroupMembers(groupId: WorkbenchGroupIdentity, visitedGroups: Set[WorkbenchGroupIdentity] = Set.empty[WorkbenchGroupIdentity]): Future[Map[WorkbenchEmail, Seq[SyncReportItem]]] = {
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
              case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
            }
          case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
        }

        googleMemberEmails <- googleDirectoryDAO.listGroupMembers(group.email) flatMap {
          case None => googleDirectoryDAO.createGroup(groupId.toString, group.email) map (_ => Set.empty[String])
          case Some(members) => Future.successful(members.toSet)
        }
        samMemberEmails <- Future.traverse(group.members) {
          case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group)

          // use proxy group email instead of user's actual email
          case userSubjectId: WorkbenchUserId => Future.successful(Option(toProxyFromUser(userSubjectId)))

          // not sure why this next case would happen but if a petSA is in a group just use its email
          case petSA: PetServiceAccountId => directoryDAO.loadSubjectEmail(petSA)
        }.map(_.collect { case Some(email) => email.value })

        toAdd = samMemberEmails -- googleMemberEmails
        toRemove = googleMemberEmails -- samMemberEmails

        addTrials <- Future.traverse(toAdd) { addEmail => googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _)) }
        removeTrials <- Future.traverse(toRemove) { removeEmail => googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _)) }

        _ <- directoryDAO.updateSynchronizedDate(groupId)
      } yield {
        Map(group.email -> Seq(addTrials, removeTrials).flatten) ++ subGroupSyncs.flatten
      }
    }
  }

  private[google] def toPetSAFromUser(user: WorkbenchUser): (ServiceAccountName, ServiceAccountDisplayName) = {
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

    (ServiceAccountName(serviceAccountName), ServiceAccountDisplayName(displayName))
  }

  override def getUserProxy(userEmail: WorkbenchEmail): Future[Option[WorkbenchEmail]] = {
    directoryDAO.loadSubjectFromEmail(userEmail).map {
      // don't attempt to handle groups or service accounts - just users
      case Some(user: WorkbenchUserId) => Option(toProxyFromUser(user))
      case _ => None
    }
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
      val accountName = toAccountName(WorkbenchEmail(googleServicesConfig.serviceAccountClientEmail))
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
