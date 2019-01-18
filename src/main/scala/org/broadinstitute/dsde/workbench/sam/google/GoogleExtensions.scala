package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream
import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.rpc.Code
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.dataaccess.NotificationDAO
import org.broadinstitute.dsde.workbench.google2.util.{DistributedLock, LockPath}
import org.broadinstitute.dsde.workbench.google.{
  GoogleDirectoryDAO,
  GoogleIamDAO,
  GoogleProjectDAO,
  GooglePubSubDAO,
  GoogleStorageDAO
}
import org.broadinstitute.dsde.workbench.google2.{
  CollectionName,
  Document
}
import org.broadinstitute.dsde.workbench.model.Notifications.Notification
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, CloudExtensionsInitializer, ManagedGroupService, SamApplication}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, SubsystemStatus, Subsystems}
import org.broadinstitute.dsde.workbench.util.{FutureSupport, Retry}
import spray.json._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object GoogleExtensions {
  val resourceId = ResourceId("google")
  val getPetPrivateKeyAction = ResourceAction("get_pet_private_key")
}

class GoogleExtensions(
    distributedLock: DistributedLock[IO],
    val directoryDAO: DirectoryDAO,
    val accessPolicyDAO: AccessPolicyDAO,
    val googleDirectoryDAO: GoogleDirectoryDAO,
    val googlePubSubDAO: GooglePubSubDAO,
    val googleIamDAO: GoogleIamDAO,
    val googleStorageDAO: GoogleStorageDAO,
    val googleProjectDAO: GoogleProjectDAO,
    val googleKeyCache: GoogleKeyCache,
    val notificationDAO: NotificationDAO,
    val googleServicesConfig: GoogleServicesConfig,
    val petServiceAccountConfig: PetServiceAccountConfig,
    val resourceTypes: Map[ResourceTypeName, ResourceType])(implicit val system: ActorSystem, executionContext: ExecutionContext, cs: ContextShift[IO])
    extends LazyLogging
    with FutureSupport
    with CloudExtensions
    with Retry {

  private val maxGroupEmailLength = 64

  private[google] def toProxyFromUser(userId: WorkbenchUserId): WorkbenchEmail =
    /* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val username = user.email.value.split("@").head
    val emailSuffix = s"_${user.id.value}@${googleServicesConfig.appsDomain}"
    val maxUsernameLength = maxGroupEmailLength - emailSuffix.length
    WorkbenchEmail(username.take(maxUsernameLength) + emailSuffix)
     */
    WorkbenchEmail(s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}PROXY_${userId.value}@${googleServicesConfig.appsDomain}")

  /**/
  override val emailDomain = googleServicesConfig.appsDomain
  private[google] val allUsersGroupEmail = WorkbenchEmail(
    s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}GROUP_${allUsersGroupName.value}@$emailDomain")

  override def getOrCreateAllUsersGroup(directoryDAO: DirectoryDAO)(implicit executionContext: ExecutionContext): Future[WorkbenchGroup] = {
    val allUsersGroup = BasicWorkbenchGroup(allUsersGroupName, Set.empty, allUsersGroupEmail)
    for {
      createdGroup <- directoryDAO.createGroup(allUsersGroup).unsafeToFuture() recover {
        case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => allUsersGroup
      }
      existingGoogleGroup <- googleDirectoryDAO.getGoogleGroup(createdGroup.email)
      _ <- existingGoogleGroup match {
        case None =>
          googleDirectoryDAO.createGroup(createdGroup.id.toString, createdGroup.email, Option(googleDirectoryDAO.lockedDownGroupSettings)) recover {
            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
          }
        case Some(_) => Future.successful(())
      }

    } yield createdGroup
  }

  override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] =
    googleDirectoryDAO.isGroupMember(WorkbenchEmail(s"fc-admins@${googleServicesConfig.appsDomain}"), memberEmail) recoverWith {
      case t => throw new WorkbenchException("Unable to query for admin status.", t)
    }

  def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit] = {
    val extensionResourceType =
      resourceTypes.getOrElse(CloudExtensions.resourceTypeName, throw new Exception(s"${CloudExtensions.resourceTypeName} resource type not found"))
    val ownerGoogleSubjectId = GoogleSubjectId(googleServicesConfig.serviceAccountClientId)
    for {
      user <- directoryDAO.loadSubjectFromGoogleSubjectId(ownerGoogleSubjectId)

      subject <- directoryDAO.loadSubjectFromGoogleSubjectId(GoogleSubjectId(googleServicesConfig.serviceAccountClientId))
      serviceAccountUserInfo <- subject match {
        case Some(uid: WorkbenchUserId) => IO.pure(UserInfo(OAuth2BearerToken(""), uid, googleServicesConfig.serviceAccountClientEmail, 0))
        case Some(_) =>
          IO.raiseError(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.Conflict, s"subjectId in configration ${googleServicesConfig.serviceAccountClientId} is not a valid user")))
        case None => IO.pure(UserInfo(OAuth2BearerToken(""), genWorkbenchUserId(System.currentTimeMillis()), googleServicesConfig.serviceAccountClientEmail, 0))
      }
      _ <- IO.fromFuture(
        IO(
          samApplication.userService.createUser(CreateWorkbenchUser(
            serviceAccountUserInfo.userId,
            GoogleSubjectId(googleServicesConfig.serviceAccountClientId),
            serviceAccountUserInfo.userEmail)) recover {
            case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) =>
          }))

      _ <- samApplication.resourceService.createResourceType(extensionResourceType)

      _ <- IO.fromFuture(IO(samApplication.resourceService.createResource(extensionResourceType, GoogleExtensions.resourceId, serviceAccountUserInfo))) handleErrorWith {
        case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => IO.unit
      }
      _ <- googleKeyCache.onBoot()
    } yield ()
  }

  // Uses Google Pub/Sub to offload creation of the group.
  // The handler for the subscription will ultimately call GoogleExtensions.synchronizeGroupMembers, which will
  // do all the heavy lifting of creating the Google Group and adding members.
  override def publishGroup(id: WorkbenchGroupName): Future[Unit] =
    googlePubSubDAO.publishMessages(googleServicesConfig.groupSyncTopic, Seq(id.toJson.compactPrint))

  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity]): Future[Unit] =
    onGroupUpdateRecursive(groupIdentities, Seq.empty).unsafeToFuture()

  private def onGroupUpdateRecursive(groupIdentities: Seq[WorkbenchGroupIdentity], visitedGroups: Seq[WorkbenchGroupIdentity]): IO[Unit] =
    for {
      idsAndSyncDates <- groupIdentities.toList.traverse { id =>
        directoryDAO.getSynchronizedDate(id).map(dateOption => id -> dateOption)
      }
      // only sync groups that have already been synchronized
      messagesForIdsWithSyncDates = idsAndSyncDates.collect {
        case (gn: WorkbenchGroupName, Some(_)) => gn.toJson.compactPrint
        case (rpn: FullyQualifiedPolicyId, Some(_)) => rpn.toJson.compactPrint
      }
      _ <- IO.fromFuture(IO(googlePubSubDAO.publishMessages(googleServicesConfig.groupSyncTopic, messagesForIdsWithSyncDates)))
      ancestorGroups <- groupIdentities.toList.traverse { id =>
        directoryDAO.listAncestorGroups(id)
      }
      managedGroupIds = (ancestorGroups.flatten ++ groupIdentities).filterNot(visitedGroups.contains).collect {
        case FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, id), ManagedGroupService.adminPolicyName | ManagedGroupService.memberPolicyName) => id
      }
      _ <- managedGroupIds.traverse(id => onManagedGroupUpdate(id, visitedGroups ++ groupIdentities ++ ancestorGroups.flatten))
    } yield ()

  private def onManagedGroupUpdate(groupId: ResourceId, visitedGroups: Seq[WorkbenchGroupIdentity]): IO[Unit] =
    for {
      resources <- accessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(groupId.value))
      policies <- resources.toList.traverse { resource =>
        accessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId)
      }
      _ <- onGroupUpdateRecursive(policies.flatten.map(_.id).toList, visitedGroups)
    } yield ()

  override def onUserCreate(user: WorkbenchUser): Future[Unit] = {
    val proxyEmail = toProxyFromUser(user.id)
    for {
      _ <- googleDirectoryDAO.createGroup(user.email.value, proxyEmail, Option(googleDirectoryDAO.lockedDownGroupSettings)) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      allUsersGroup <- getOrCreateAllUsersGroup(directoryDAO)
      _ <- googleDirectoryDAO.addMemberToGroup(allUsersGroup.email, proxyEmail)

      /* Re-enable this code after fixing rawls for GAWB-2933
      _ <- directoryDAO.addProxyGroup(user.id, proxyEmail)
     */
    } yield ()
  }

  override def getUserStatus(user: WorkbenchUser): Future[Boolean] =
    getUserProxy(user.id).flatMap {
      case Some(proxyEmail) => googleDirectoryDAO.isGroupMember(proxyEmail, WorkbenchEmail(user.email.value))
      case None => Future.successful(false)
    }

  /**
    * Evaluate a future for each pet in parallel.
    */
  private def forAllPets[T](userId: WorkbenchUserId)(f: PetServiceAccount => Future[T]): Future[Seq[T]] =
    for {
      pets <- directoryDAO.getAllPetServiceAccountsForUser(userId)
      a <- Future.traverse(pets) { pet =>
        f(pet)
      }
    } yield a

  override def onUserEnable(user: WorkbenchUser): Future[Unit] =
    for {
      _ <- withProxyEmail(user.id) { proxyEmail =>
        googleDirectoryDAO.addMemberToGroup(proxyEmail, WorkbenchEmail(user.email.value))
      }
      _ <- forAllPets(user.id) { enablePetServiceAccount }
    } yield ()

  override def onUserDisable(user: WorkbenchUser): Future[Unit] =
    for {
      _ <- forAllPets(user.id) { disablePetServiceAccount }
      _ <- withProxyEmail(user.id) { proxyEmail =>
        googleDirectoryDAO.removeMemberFromGroup(proxyEmail, WorkbenchEmail(user.email.value))
      }
    } yield ()

  override def onUserDelete(userId: WorkbenchUserId): Future[Unit] =
    for {
      _ <- withProxyEmail(userId) { googleDirectoryDAO.deleteGroup }
      _ <- forAllPets(userId) { pet =>
        googleIamDAO.removeServiceAccount(pet.id.project, toAccountName(pet.serviceAccount.email))
      }
      _ <- forAllPets(userId) { pet =>
        directoryDAO.deletePetServiceAccount(pet.id).unsafeToFuture()
      }
    } yield ()

  override def onGroupDelete(groupEmail: WorkbenchEmail): Future[Unit] =
    googleDirectoryDAO.deleteGroup(groupEmail)

  @deprecated("Use new two-argument version of this function", "Sam Phase 3")
  def createUserPetServiceAccount(user: WorkbenchUser): Future[PetServiceAccount] =
    createUserPetServiceAccount(user, petServiceAccountConfig.googleProject).unsafeToFuture()

  @deprecated("Use new two-argument version of this function", "Sam Phase 3")
  def deleteUserPetServiceAccount(userId: WorkbenchUserId): Future[Boolean] =
    deleteUserPetServiceAccount(userId, petServiceAccountConfig.googleProject).unsafeToFuture() //TODO: shall we delete these deprecated methods

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject): IO[Boolean] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      deletedSomething <- maybePet match {
        case Some(pet) =>
          for {
            _ <- directoryDAO.deletePetServiceAccount(PetServiceAccountId(userId, project))
            _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccount(project, toAccountName(pet.serviceAccount.email))))
          } yield true

        case None => IO.pure(false) // didn't find the pet, nothing to delete
      }
    } yield deletedSomething

  def createUserPetServiceAccount(user: WorkbenchUser, project: GoogleProject): IO[PetServiceAccount] = {
    val (petSaName, petSaDisplayName) = toPetSAFromUser(user)
    // The normal situation is that the pet either exists in both ldap and google or neither.
    // Sometimes, especially in tests, the pet may be removed from ldap, but not google or the other way around.
    // This code is a little extra complicated to detect the cases when a pet does not exist in google, ldap or both
    // and do the right thing.
    val createPet = for {
      (maybePet, maybeServiceAccount) <- retrievePetAndSA(user.id, petSaName, project)
      serviceAccount <- maybeServiceAccount match {
        // SA does not exist in google, create it and add it to the proxy group
        case None =>
          for {
            sa <- IO.fromFuture(IO(googleIamDAO.createServiceAccount(project, petSaName, petSaDisplayName)))
            r <- IO.fromFuture(IO(withProxyEmail(user.id) { proxyEmail =>
              googleDirectoryDAO.addMemberToGroup(proxyEmail, sa.email)
            }))
          } yield sa
        // SA already exists in google, use it
        case Some(sa) => IO.pure(sa)
      }
      pet <- (maybePet, maybeServiceAccount) match {
        // pet does not exist in ldap, create it and enable the identity
        case (None, _) =>
          for {
            p <- directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, project), serviceAccount))
            _ <- directoryDAO.enableIdentity(p.id)
          } yield p
        // pet already exists in ldap, but a new SA was created so update ldap with new SA info
        case (Some(p), None) =>
          directoryDAO.updatePetServiceAccount(p.copy(serviceAccount = serviceAccount))

        // everything already existed
        case (Some(p), Some(_)) => IO.pure(p)
      }
    } yield pet

    val lock = LockPath(CollectionName(s"${project.value}-createPet"), Document(user.id.value), 30 seconds)

    for {
      (pet, sa) <- retrievePetAndSA(user.id, petSaName, project) //I'm loving better-monadic-for
      shouldLock = !(pet.isDefined && sa.isDefined) // if either is not defined, we need to lock and potentially create them; else we return the pet
      p <- if (shouldLock) distributedLock.withLock(lock).use(_ => createPet) else pet.get.pure[IO]
    } yield p
  }

  private def retrievePetAndSA(
      userId: WorkbenchUserId,
      petServiceAccountName: ServiceAccountName,
      project: GoogleProject): IO[(Option[PetServiceAccount], Option[ServiceAccount])] = {
    val serviceAccount = IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, petServiceAccountName)))
    val pet = directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
    (pet, serviceAccount).parTupled
  }

  def getPetServiceAccountKey(userEmail: WorkbenchEmail, project: GoogleProject): IO[Option[String]] =
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail)
      key <- subject match {
        case Some(userId: WorkbenchUserId) => getPetServiceAccountKey(WorkbenchUser(userId, None, userEmail), project).map(Option(_))
        case _ => IO.pure(None)
      }
    } yield key

  def getPetServiceAccountKey(user: WorkbenchUser, project: GoogleProject): IO[String] =
    for {
      pet <- createUserPetServiceAccount(user, project)
      key <- googleKeyCache.getKey(pet)
    } yield key

  def getPetServiceAccountToken(user: WorkbenchUser, project: GoogleProject, scopes: Set[String]): Future[String] =
    getPetServiceAccountKey(user, project).unsafeToFuture().flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  def getArbitraryPetServiceAccountKey(user: WorkbenchUser): Future[String] =
    getDefaultServiceAccountForShellProject(user)

  def getArbitraryPetServiceAccountToken(user: WorkbenchUser, scopes: Set[String]): Future[String] =
    getArbitraryPetServiceAccountKey(user).flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  private def getDefaultServiceAccountForShellProject(user: WorkbenchUser): Future[String] = {
    val projectName = s"fc-${googleServicesConfig.environment.substring(0, Math.min(googleServicesConfig.environment.length(), 5))}-${user.id.value}" //max 30 characters. subject ID is 21
    for {
      creationOperationId <- googleProjectDAO.createProject(projectName).map(opId => Option(opId)) recover {
        case gjre: GoogleJsonResponseException if gjre.getDetails.getCode == StatusCodes.Conflict.intValue => None
      }
      _ <- creationOperationId match {
        case Some(opId) => pollShellProjectCreation(opId) //poll until it's created
        case None => Future.successful(())
      }
      key <- getPetServiceAccountKey(user, GoogleProject(projectName)).unsafeToFuture()
    } yield key
  }

  private def pollShellProjectCreation(operationId: String): Future[Boolean] = {
    def whenCreating(throwable: Throwable): Boolean =
      throwable match {
        case t: WorkbenchException => throw t
        case t: Exception => true
        case _ => false
      }

    retryExponentially(whenCreating)(() => {
      googleProjectDAO.pollOperation(operationId).map { operation =>
        if (operation.getDone && Option(operation.getError).exists(_.getCode.intValue() == Code.ALREADY_EXISTS.getNumber)) true
        else if (operation.getDone && Option(operation.getError).isEmpty) true
        else if (operation.getDone && Option(operation.getError).isDefined)
          throw new WorkbenchException(s"project creation failed with error ${operation.getError.getMessage}")
        else throw new Exception("project still creating...")
      }
    })
  }

  def getAccessTokenUsingJson(saKey: String, desiredScopes: Set[String]): Future[String] = Future {
    val keyStream = new ByteArrayInputStream(saKey.getBytes)
    val credential = ServiceAccountCredentials.fromStream(keyStream).createScoped(desiredScopes.asJava)
    credential.refreshAccessToken.getTokenValue
  }

  def removePetServiceAccountKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): IO[Unit] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      result <- maybePet match {
        case Some(pet) => googleKeyCache.removeKey(pet, keyId)
        case None => IO.unit
      }
    } yield result

  private def enablePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] =
    for {
      _ <- directoryDAO.enableIdentity(petServiceAccount.id).unsafeToFuture()
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        googleDirectoryDAO.addMemberToGroup(proxyEmail, petServiceAccount.serviceAccount.email)
      }
    } yield ()

  private def disablePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] =
    for {
      _ <- directoryDAO.disableIdentity(petServiceAccount.id)
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        googleDirectoryDAO.removeMemberFromGroup(proxyEmail, petServiceAccount.serviceAccount.email)
      }
    } yield ()

  private def getPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] =
    directoryDAO.getAllPetServiceAccountsForUser(userId)

  private def removePetServiceAccount(petServiceAccount: PetServiceAccount): Future[Unit] =
    for {
      // disable the pet service account
      _ <- disablePetServiceAccount(petServiceAccount)
      // remove the LDAP record for the pet service account
      _ <- directoryDAO.deletePetServiceAccount(petServiceAccount.id).unsafeToFuture()
      // remove the service account itself in Google
      _ <- googleIamDAO.removeServiceAccount(petServiceAccountConfig.googleProject, toAccountName(petServiceAccount.serviceAccount.email))
    } yield ()

  def getSynchronizedState(groupId: WorkbenchGroupIdentity): IO[Option[GroupSyncResponse]] = {
    val groupDate = getSynchronizedDate(groupId)
    val groupEmail = getSynchronizedEmail(groupId)

    for {
      dateOpt <- groupDate
      emailOpt <- groupEmail
    } yield {
      (dateOpt, emailOpt) match {
        case (Some(date), Some(email)) => Option(GroupSyncResponse(date.toString, email))
        case _ => None
      }
    }
  }

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] =
    directoryDAO.getSynchronizedDate(groupId)

  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] =
    directoryDAO.getSynchronizedEmail(groupId)

  private[google] def toPetSAFromUser(user: WorkbenchUser): (ServiceAccountName, ServiceAccountDisplayName) = {
    /*
     * Service account IDs must be:
     * 1. between 6 and 30 characters
     * 2. lower case alphanumeric separated by hyphens
     * 3. must start with a lower case letter
     *
     * Subject IDs are 22 numeric characters, so "pet-${subjectId}" fulfills these requirements.
     */
    val serviceAccountName = s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}pet-${user.id.value}"
    val displayName = s"Pet Service Account for user [${user.email.value}]"

    // Display names have a max length of 100 characters
    (ServiceAccountName(serviceAccountName), ServiceAccountDisplayName(displayName.take(100)))
  }

  override def fireAndForgetNotifications[T <: Notification](notifications: Set[T]): Unit =
    notificationDAO.fireAndForgetNotifications(notifications)

  override def getUserProxy(userEmail: WorkbenchEmail): Future[Option[WorkbenchEmail]] =
    directoryDAO.loadSubjectFromEmail(userEmail).unsafeToFuture().flatMap {
      case Some(user: WorkbenchUserId) => getUserProxy(user)
      case Some(pet: PetServiceAccountId) => getUserProxy(pet.userId)
      case _ => Future.successful(None)
    }

  private[google] def getUserProxy(userId: WorkbenchUserId): Future[Option[WorkbenchEmail]] =
    /* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    directoryDAO.readProxyGroup(userId)
     */
    Future.successful(Some(toProxyFromUser(userId)))

  private def withProxyEmail[T](userId: WorkbenchUserId)(f: WorkbenchEmail => Future[T]): Future[T] =
    getUserProxy(userId) flatMap {
      case Some(e) => f(e)
      case None =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"Proxy group does not exist for subject ID: $userId"))
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
      val accountName = toAccountName(googleServicesConfig.serviceAccountClientEmail)
      googleIamDAO.findServiceAccount(googleServicesConfig.serviceAccountClientProject, accountName).map {
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

case class GoogleExtensionsInitializer(cloudExtensions: GoogleExtensions, googleGroupSynchronizer: GoogleGroupSynchronizer) extends CloudExtensionsInitializer {
  override def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit] = {
    system.actorOf(
      GoogleGroupSyncMonitorSupervisor.props(
        cloudExtensions.googleServicesConfig.groupSyncPollInterval,
        cloudExtensions.googleServicesConfig.groupSyncPollJitter,
        cloudExtensions.googlePubSubDAO,
        cloudExtensions.googleServicesConfig.groupSyncTopic,
        cloudExtensions.googleServicesConfig.groupSyncSubscription,
        cloudExtensions.googleServicesConfig.groupSyncWorkerCount,
        googleGroupSynchronizer
      ))

    cloudExtensions.onBoot(samApplication)
  }
}
