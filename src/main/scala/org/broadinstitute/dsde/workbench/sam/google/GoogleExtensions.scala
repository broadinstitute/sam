package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.{Clock, IO}
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.BlobId
import com.google.protobuf.{Duration, Timestamp}
import com.typesafe.scalalogging.LazyLogging
import net.logstash.logback.argument.StructuredArguments
import org.broadinstitute.dsde.workbench.dataaccess.NotificationDAO
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.MessageRequest
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO, GoogleKmsService, GoogleProjectDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model.Notifications.Notification
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{ActionServiceAccount, ActionServiceAccountId, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, CloudExtensionsInitializer, ManagedGroupService, SamApplication}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, SubsystemStatus, Subsystems}
import org.broadinstitute.dsde.workbench.util.{FutureSupport, Retry}
import spray.json._

import java.io.ByteArrayInputStream
import java.net.URL
import java.util.Date
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object GoogleExtensions {
  val resourceId = ResourceId("google")
  val getPetPrivateKeyAction = ResourceAction("get_pet_private_key")
}

class GoogleExtensions(
    distributedLock: PostgresDistributedLockDAO[IO],
    val directoryDAO: DirectoryDAO,
    val accessPolicyDAO: AccessPolicyDAO,
    val googleDirectoryDAO: GoogleDirectoryDAO,
    val notificationPubSubDAO: GooglePubSubDAO,
    val googleGroupSyncPubSubDAO: GooglePubSubDAO,
    val googleDisableUsersPubSubDAO: GooglePubSubDAO,
    val googleIamDAO: GoogleIamDAO,
    val googleStorageDAO: GoogleStorageDAO,
    val googleProjectDAO: GoogleProjectDAO,
    val googleKeyCache: GoogleKeyCache,
    val notificationDAO: NotificationDAO,
    val googleKms: GoogleKmsService[IO],
    val googleStorageService: GoogleStorageService[IO],
    val googleServicesConfig: GoogleServicesConfig,
    val petServiceAccountConfig: PetServiceAccountConfig,
    val resourceTypes: Map[ResourceTypeName, ResourceType],
    val superAdminsGroup: WorkbenchEmail
)(implicit val system: ActorSystem, executionContext: ExecutionContext, clock: Clock[IO])
    extends ProxyEmailSupport(googleServicesConfig)
    with LazyLogging
    with FutureSupport
    with CloudExtensions
    with Retry {

  val petServiceAccounts: PetServiceAccounts =
    new PetServiceAccounts(distributedLock, googleIamDAO, googleDirectoryDAO, googleProjectDAO, googleKeyCache, directoryDAO, googleServicesConfig)
  val petSigningAccounts: PetSigningAccounts =
    new PetSigningAccounts(distributedLock, googleIamDAO, googleDirectoryDAO, googleProjectDAO, googleKeyCache, directoryDAO, googleServicesConfig)
  val actionServiceAccounts: ActionServiceAccounts =
    new ActionServiceAccounts(distributedLock, googleIamDAO, googleProjectDAO, directoryDAO, googleServicesConfig)

  private val maxGroupEmailLength = 64

  private val excludeFromPetSigningAccount: Set[WorkbenchEmail] = Set(googleServicesConfig.serviceAccountClientEmail)

  override val emailDomain = googleServicesConfig.appsDomain

  private[google] val allUsersGroupEmail = WorkbenchEmail(
    s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}GROUP_${CloudExtensions.allUsersGroupName.value}@$emailDomain"
  )

  private[google] val allPetSigningAccountsGroupEmail = WorkbenchEmail(
    s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}GROUP_${CloudExtensions.allPetSingingAccountsGroupName.value}@$emailDomain"
  )

  private val userProjectQueryParam = "userProject"
  private val requestedByQueryParam = "requestedBy"
  private val defaultSignedUrlDuration = 60L

  override def getOrCreateAllUsersGroup(directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext)(implicit
      executionContext: ExecutionContext
  ): IO[WorkbenchGroup] = {
    val allUsersGroupStub = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set.empty, allUsersGroupEmail)
    for {
      existingGroup <- directoryDAO.loadGroup(allUsersGroupStub.id, samRequestContext = samRequestContext)
      allUsersGroup <- existingGroup match {
        case None => directoryDAO.createGroup(allUsersGroupStub, samRequestContext = samRequestContext)
        case Some(group) => IO.pure(group)
      }
      existingGoogleGroup <- IO.fromFuture(IO(googleDirectoryDAO.getGoogleGroup(allUsersGroup.email)))
      _ <- existingGoogleGroup match {
        case None =>
          IO.fromFuture(
            IO(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email, Option(googleDirectoryDAO.lockedDownGroupSettings)))
          ) recover {
            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
          }
        case Some(_) => IO.unit
      }

    } yield allUsersGroup
  }

  def getOrCreateAllPetSigningAccountsGroup(directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext): IO[WorkbenchGroup] = {
    val allPetSigningAccountsGroupStub = BasicWorkbenchGroup(CloudExtensions.allPetSingingAccountsGroupName, Set.empty, allPetSigningAccountsGroupEmail)
    for {
      existingGroup <- directoryDAO.loadGroup(allPetSigningAccountsGroupStub.id, samRequestContext = samRequestContext)
      allPetSigningAccountsGroup <- existingGroup match {
        case None => directoryDAO.createGroup(allPetSigningAccountsGroupStub, samRequestContext = samRequestContext)
        case Some(group) => IO.pure(group)
      }
      existingGoogleGroup <- IO.fromFuture(IO(googleDirectoryDAO.getGoogleGroup(allPetSigningAccountsGroup.email)))
      _ <- existingGoogleGroup match {
        case None =>
          IO.fromFuture(
            IO(
              googleDirectoryDAO
                .createGroup(allPetSigningAccountsGroup.id.toString, allPetSigningAccountsGroup.email, Option(googleDirectoryDAO.lockedDownGroupSettings))
            )
          ) recover {
            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
          }
        case Some(_) => IO.unit
      }

    } yield allPetSigningAccountsGroup
  }

  override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] =
    googleDirectoryDAO.isGroupMember(WorkbenchEmail(s"fc-admins@${googleServicesConfig.appsDomain}"), memberEmail) recoverWith { case t =>
      throw new WorkbenchException("Unable to query for admin status.", t)
    }

  override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] =
    googleDirectoryDAO.isGroupMember(superAdminsGroup, memberEmail) recoverWith { case t =>
      throw new WorkbenchException("Unable to query for admin status.", t)
    }

  def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit] = {
    val samRequestContext = SamRequestContext() // `SamRequestContext()` is used so that we don't trace 1-off boot/init methods
    val extensionResourceType =
      resourceTypes.getOrElse(CloudExtensions.resourceTypeName, throw new Exception(s"${CloudExtensions.resourceTypeName} resource type not found"))
    val googleSubjectId = GoogleSubjectId(googleServicesConfig.serviceAccountClientId)
    for {
      maybeSamUser <- directoryDAO.loadUserByGoogleSubjectId(googleSubjectId, samRequestContext)
      samUser <- maybeSamUser match {
        case Some(samUser) => IO.pure(samUser)
        case None =>
          directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext).flatMap {
            case Some(_) =>
              IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $googleSubjectId is not a SamUser")))
            case None =>
              val newUser = SamUser(
                genWorkbenchUserId(System.currentTimeMillis()),
                Option(googleSubjectId),
                googleServicesConfig.serviceAccountClientEmail,
                None,
                false
              )
              samApplication.userService.createUser(newUser, samRequestContext).map(_ => newUser)
          }
      }

      _ <- getOrCreateAllPetSigningAccountsGroup(directoryDAO, samRequestContext)

      _ <- googleKms.createKeyRing(
        googleServicesConfig.googleKms.project,
        googleServicesConfig.googleKms.location,
        googleServicesConfig.googleKms.keyRingId
      ) handleErrorWith { case _: AlreadyExistsException => IO.unit }

      _ <- googleKms.createKey(
        googleServicesConfig.googleKms.project,
        googleServicesConfig.googleKms.location,
        googleServicesConfig.googleKms.keyRingId,
        googleServicesConfig.googleKms.keyId,
        Option(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000 + googleServicesConfig.googleKms.rotationPeriod.toSeconds).build()),
        Option(Duration.newBuilder().setSeconds(googleServicesConfig.googleKms.rotationPeriod.toSeconds).build())
      ) handleErrorWith { case _: AlreadyExistsException => IO.unit }

      _ <- googleKms.addMemberToKeyPolicy(
        googleServicesConfig.googleKms.project,
        googleServicesConfig.googleKms.location,
        googleServicesConfig.googleKms.keyRingId,
        googleServicesConfig.googleKms.keyId,
        s"group:$allUsersGroupEmail",
        "roles/cloudkms.cryptoKeyEncrypterDecrypter"
      )

      _ <- samApplication.resourceService.createResourceType(extensionResourceType, samRequestContext)

      _ <- samApplication.resourceService.createResource(extensionResourceType, GoogleExtensions.resourceId, samUser, samRequestContext) handleErrorWith {
        case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => IO.unit
      }
      _ <- googleKeyCache.onBoot()
    } yield ()
  }

  // Uses Google Pub/Sub to offload creation of the group.
  // The handler for the subscription will ultimately call GoogleExtensions.synchronizeGroupMembers, which will
  // do all the heavy lifting of creating the Google Group and adding members.
  override def publishGroup(id: WorkbenchGroupName): Future[Unit] =
    googleGroupSyncPubSubDAO.publishMessages(googleServicesConfig.groupSyncPubSubConfig.topic, Seq(MessageRequest(id.toJson.compactPrint)))

  /*
    - managed groups and access policies are both "groups"
    - You can have a bunch of resources constrained an auth domain (a collection of managed groups).
    - A user must be a member of the auth domain in order to access some actions on the resources in that auth domain.
    - The user must be a member of all groups in an auth domain in order to access a resource
    - An access policy is specific to a single resource
    - To access an action on a resource, a user must BOTH be a member of the auth domain of the resource AND also be a member
      of one of the access policies that has the action.
    - When someone gets added to an managed group or access policy, we have to figure out which google groups they suddenly have access to.
    - To do this, when someone gets added to a group, we call onGroupUpdate, which retrieves a list of all of the groups that are affected
      by the groupUpdate, and for each publishes a message to a pub/sub queue telling a Sam background process
      to sync the user's access for all of those groups.
    - To figure out which groups are affected - we first separate access policies and managed groups.
      For each access policy passed to onGroupUpdate, we publish a message to sync just that group
      For each managed group passed to onGroupUpdate (these might be used in auth domains)
        - find all ancestor groups because the updated group may be a sub group
        - find resources that have any of these groups in their auth domain
        - publish a message for each access policy of all those resources

     see GoogleGroupSynchronizer for the background process that does the group synchronization
   */
  override def onGroupUpdate(groupIdentities: Seq[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Unit] =
    for {
      start <- clock.monotonic
      // only sync groups that have been synchronized in the past
      previouslySyncedIds <- groupIdentities.toList.traverseFilter { id =>
        directoryDAO.getSynchronizedDate(id, samRequestContext).map(dateOption => dateOption.map(_ => id))
      }

      // make all the publish messages for the previously synced groups
      messages <- previouslySyncedIds.traverse {
        // it is a group that isn't an access policy, could be a managed group
        case groupName: WorkbenchGroupName =>
          makeConstrainedResourceAccessPolicyMessages(groupName, samRequestContext).map(_ :+ groupName.toJson.compactPrint)

        // it is the admin or member access policy of a managed group
        case accessPolicyId @ FullyQualifiedPolicyId(
              FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, id),
              ManagedGroupService.adminPolicyName | ManagedGroupService.memberPolicyName
            ) =>
          makeConstrainedResourceAccessPolicyMessages(accessPolicyId, samRequestContext).map(_ :+ accessPolicyId.toJson.compactPrint)

        // it is an access policy on a resource that's not a managed group
        case accessPolicyId: FullyQualifiedPolicyId => IO.pure(List(accessPolicyId.toJson.compactPrint))
      }

      // publish all the messages
      _ <- IO.fromFuture(IO(publishMessages(messages.flatten.map(MessageRequest(_)))))

      end <- clock.monotonic

    } yield {
      val duration = end - start
      logger.info(
        s"GoogleExtensions.onGroupUpdate timing (ms)",
        StructuredArguments.entries(Map("duration" -> duration.toMillis, "group-ids" -> groupIdentities.map(_.toString).asJava).asJava)
      )
    }

  private def makeConstrainedResourceAccessPolicyMessages(groupIdentity: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[List[String]] =
    // start with a group
    for {
      // get all the ancestors of that group
      ancestorGroupsOfManagedGroups <- directoryDAO.listAncestorGroups(groupIdentity, samRequestContext)

      // get all the ids of the group and its ancestors
      managedGroupIds = (ancestorGroupsOfManagedGroups + groupIdentity).collect {
        case FullyQualifiedPolicyId(
              FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, id),
              ManagedGroupService.adminPolicyName | ManagedGroupService.memberPolicyName
            ) =>
          WorkbenchGroupName(id.value)
      }

      // get all access policies on any resource that is constrained by the groups
      constrainedResourceAccessPolicyIds <- managedGroupIds.toList.traverse(
        accessPolicyDAO.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(_, samRequestContext)
      )

      // return messages for all the affected access policies and the original group we started with
    } yield constrainedResourceAccessPolicyIds.flatten.map(accessPolicyId => accessPolicyId.toJson.compactPrint)

  private def publishMessages(messages: Seq[MessageRequest]): Future[Unit] =
    googleGroupSyncPubSubDAO.publishMessages(googleServicesConfig.groupSyncPubSubConfig.topic, messages)

  override def onUserCreate(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] = {
    val proxyEmail = toProxyFromUser(user.id)
    for {
      _ <- IO.fromFuture(IO(googleDirectoryDAO.createGroup(user.email.value, proxyEmail, Option(googleDirectoryDAO.lockedDownGroupSettings)))) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.Conflict.intValue => ()
      }
      allUsersGroup <- getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      _ <- IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(allUsersGroup.email, proxyEmail)))
      _ <-
        if (excludeFromPetSigningAccount.contains(user.email) || !googleServicesConfig.petSigningAccountsEnabled) IO.none
        else
          for {
            petSigningAccount <- petSigningAccounts.createPetSigningAccountForUser(user, samRequestContext)
            _ <- IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(allPetSigningAccountsGroupEmail, petSigningAccount.serviceAccount.email)))
          } yield ()
    } yield ()
  }

  override def getUserStatus(user: SamUser): IO[Boolean] =
    getUserProxy(user.id).flatMap {
      case Some(proxyEmail) => IO.fromFuture(IO(googleDirectoryDAO.isGroupMember(proxyEmail, WorkbenchEmail(user.email.value))))
      case None => IO.pure(false)
    }

  override def onUserEnable(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- withProxyEmail(user.id) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(proxyEmail, WorkbenchEmail(user.email.value))))
      }
      _ <- petServiceAccounts.forAllPets(user.id, samRequestContext)((petServiceAccount: PetServiceAccount) =>
        petServiceAccounts.enablePetServiceAccount(petServiceAccount, samRequestContext)
      )
    } yield ()

  override def onUserDisable(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- petServiceAccounts.forAllPets(user.id, samRequestContext)((petServiceAccount: PetServiceAccount) =>
        petServiceAccounts.disablePetServiceAccount(petServiceAccount, samRequestContext)
      )
      _ <- withProxyEmail(user.id) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.removeMemberFromGroup(proxyEmail, WorkbenchEmail(user.email.value))))
      }
    } yield ()

  override def onUserDelete(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- petServiceAccounts.forAllPets(userId, samRequestContext)((petServiceAccount: PetServiceAccount) =>
        petServiceAccounts.removePetServiceAccount(petServiceAccount, samRequestContext)
      )
      _ <- petSigningAccounts.removePetSigningAccount(userId, samRequestContext)
      _ <- withProxyEmail(userId)(email => IO.fromFuture(IO(googleDirectoryDAO.deleteGroup(email))))
    } yield ()

  override def onGroupDelete(groupEmail: WorkbenchEmail): IO[Unit] =
    IO.fromFuture(IO(googleDirectoryDAO.deleteGroup(groupEmail)))

  override def onResourceDelete(resourceId: ResourceId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- actionServiceAccounts.forAllActionServiceAccounts(resourceId, samRequestContext)((actionServiceAccount: ActionServiceAccount) =>
        actionServiceAccounts.removeActionServiceAccount(actionServiceAccount, samRequestContext)
      )
    } yield ()

  def getSynchronizedState(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[GroupSyncResponse]] = {
    val groupDate = getSynchronizedDate(groupId, samRequestContext)
    val groupEmail = getSynchronizedEmail(groupId, samRequestContext)

    for {
      dateOpt <- groupDate
      emailOpt <- groupEmail
    } yield (dateOpt, emailOpt) match {
      case (Some(date), Some(email)) => Option(GroupSyncResponse(date.toString, email))
      case _ => None
    }
  }

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]] =
    directoryDAO.getSynchronizedDate(groupId, samRequestContext)

  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.getSynchronizedEmail(groupId, samRequestContext)

  override def fireAndForgetNotifications[T <: Notification](notifications: Set[T]): Unit =
    notificationDAO.fireAndForgetNotifications(notifications)

  override def getUserProxy(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext).flatMap {
      case Some(user: WorkbenchUserId) => getUserProxy(user)
      case Some(pet: PetServiceAccountId) => getUserProxy(pet.userId)
      case _ => IO.pure(None)
    }

  override def checkStatus: Map[Subsystems.Subsystem, Future[SubsystemStatus]] = {
    import HealthMonitor._

    def checkGroups: Future[SubsystemStatus] = {
      logger.debug("Checking Google Groups...")
      for {
        groupOption <- googleDirectoryDAO.getGoogleGroup(allUsersGroupEmail)
      } yield groupOption match {
        case Some(_) => OkStatus
        case None => failedStatus(s"could not find group ${allUsersGroupEmail} in google")
      }
    }

    def checkPubsub: Future[SubsystemStatus] = {
      logger.debug("Checking PubSub topics...")
      case class TopicToDaoPair(
          topic: String,
          dao: GooglePubSubDAO
      )

      val pubSubDaoToCheck = List(
        TopicToDaoPair(googleServicesConfig.groupSyncPubSubConfig.topic, googleGroupSyncPubSubDAO),
        TopicToDaoPair(googleServicesConfig.notificationTopic, notificationPubSubDAO),
        TopicToDaoPair(googleServicesConfig.disableUsersPubSubConfig.topic, googleDisableUsersPubSubDAO),
        TopicToDaoPair(googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.topic, googleKeyCache.googleKeyCachePubSubDao)
      )
      for {
        listOfUnfoundTopics <- Future.traverse(pubSubDaoToCheck) { pair =>
          pair.dao.getTopic(pair.topic).map {
            case Some(_) => None
            case None => Some(pair.topic)
          }
        }
        flattenedListOfUnfoundTopics = listOfUnfoundTopics.flatten
      } yield
        if (flattenedListOfUnfoundTopics.isEmpty) {
          OkStatus
        } else {
          failedStatus(s"Could not find topic(s): ${flattenedListOfUnfoundTopics.toString}")
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

  private def fromGsPath(gsPath: String) = {
    val pattern = "gs://.*/.*".r
    if (!pattern.matches(gsPath)) {
      throw new IllegalArgumentException(s"$gsPath is not a valid gsutil URI (i.e. \"gs://bucket/blob\")")
    }
    val blobNameStartIndex = gsPath.indexOf('/', 5)
    val bucketName = gsPath.substring(5, blobNameStartIndex)
    val blobName = gsPath.substring(blobNameStartIndex + 1)
    BlobId.of(bucketName, blobName)
  }

  def getRequesterPaysSignedUrl(
      samUser: SamUser,
      gsPath: String,
      duration: Option[Long],
      requesterPaysProject: Option[GoogleProject],
      samRequestContext: SamRequestContext
  ): IO[URL] = {
    val urlParamsMap: Map[String, String] = requesterPaysProject.map(p => Map(userProjectQueryParam -> p.value)).getOrElse(Map.empty)
    val blobId = fromGsPath(gsPath)
    val bucket = GcsBucketName(blobId.getBucket)
    val objectName = GcsBlobName(blobId.getName)
    for {
      petKey <- IO.fromFuture(IO(petServiceAccounts.getArbitraryPetServiceAccountKey(samUser, samRequestContext)))
      serviceAccountCredentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(petKey.getBytes()))
      url <- getSignedUrl(samUser, bucket, objectName, duration, urlParamsMap, serviceAccountCredentials)
    } yield url
  }

  def getRequesterPaysSignedUrl(
      samUser: SamUser,
      resourceId: ResourceId,
      resourceAction: ResourceAction,
      googleProject: GoogleProject,
      gsPath: String,
      duration: Option[Long],
      requesterPaysProject: Option[GoogleProject],
      samRequestContext: SamRequestContext
  ): IO[URL] = {
    val urlParamsMap: Map[String, String] = requesterPaysProject.map(p => Map(userProjectQueryParam -> p.value)).getOrElse(Map.empty)
    val blobId = fromGsPath(gsPath)
    val bucket = GcsBucketName(blobId.getBucket)
    val objectName = GcsBlobName(blobId.getName)
    directoryDAO
      .loadActionServiceAccount(ActionServiceAccountId(resourceId, resourceAction, googleProject), samRequestContext)
      .flatMap {
        case Some(actionServiceAccount) =>
          for {
            petSigningAccountKey <- petSigningAccounts.getUserPetSigningAccount(samUser, samRequestContext)
          } yield petSigningAccountKey.map((actionServiceAccount, _))
        case None => IO.none[(ActionServiceAccount, String)]
      }
      .flatMap {
        case Some((actionServiceAccount, petSigningAccountKey)) =>
          val serviceAccountCredentials = ServiceAccountCredentials
            .fromStream(new ByteArrayInputStream(petSigningAccountKey.getBytes()))
            .createDelegated(actionServiceAccount.serviceAccount.email.value)
            .asInstanceOf[ServiceAccountCredentials]
          getSignedUrl(samUser, bucket, objectName, duration, urlParamsMap, serviceAccountCredentials)
        case None => getRequesterPaysSignedUrl(samUser, gsPath, duration, requesterPaysProject, samRequestContext)
      }
  }

  def getSignedUrl(
      samUser: SamUser,
      project: GoogleProject,
      bucket: GcsBucketName,
      name: GcsBlobName,
      duration: Option[Long],
      requesterPays: Boolean,
      samRequestContext: SamRequestContext
  ): IO[URL] = {
    val urlParamsMap: Map[String, String] = if (requesterPays) Map(userProjectQueryParam -> project.value) else Map.empty
    for {
      petServiceAccount <- petServiceAccounts.createUserPetServiceAccount(samUser, project, samRequestContext)
      petKey <- googleKeyCache.getKey(petServiceAccount)
      serviceAccountCredentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(petKey.getBytes()))
      url <- getSignedUrl(samUser, bucket, name, duration, urlParamsMap, serviceAccountCredentials)
    } yield url
  }

  private def getSignedUrl(
      samUser: SamUser,
      bucket: GcsBucketName,
      name: GcsBlobName,
      duration: Option[Long],
      urlParams: Map[String, String],
      credentials: ServiceAccountCredentials
  ): IO[URL] = {
    val timeInMinutes = duration.getOrElse(defaultSignedUrlDuration)
    val queryParams = urlParams + (requestedByQueryParam -> samUser.email.value)
    googleStorageService
      .getSignedBlobUrl(
        bucket,
        name,
        credentials,
        expirationTime = timeInMinutes,
        expirationTimeUnit = TimeUnit.MINUTES,
        queryParams = queryParams
      )
      .compile
      .lastOrError
  }

  override val allSubSystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.GooglePubSub, Subsystems.GoogleIam)

  override def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject, samRequestContext: SamRequestContext): IO[Boolean] =
    petServiceAccounts.deleteUserPetServiceAccount(userId, project, samRequestContext)

}

case class GoogleExtensionsInitializer(cloudExtensions: GoogleExtensions, googleGroupSynchronizer: GoogleGroupSynchronizer) extends CloudExtensionsInitializer {
  override def onBoot(samApplication: SamApplication)(implicit system: ActorSystem): IO[Unit] =
    for {
      googleGroupSyncIoRuntime <- GooglePubSubMonitor.createReceiverIORuntime(cloudExtensions.googleServicesConfig.groupSyncPubSubConfig)
      _ <- googleGroupSynchronizer.init()
      _ <- new GooglePubSubMonitor(
        cloudExtensions.googleGroupSyncPubSubDAO,
        cloudExtensions.googleServicesConfig.groupSyncPubSubConfig,
        cloudExtensions.googleServicesConfig.serviceAccountCredentialJson,
        new GoogleGroupSyncMessageReceiver(googleGroupSynchronizer)(googleGroupSyncIoRuntime)
      ).startAndRegisterTermination()

      disableUserIoRuntime <- GooglePubSubMonitor.createReceiverIORuntime(cloudExtensions.googleServicesConfig.disableUsersPubSubConfig)
      _ <- new GooglePubSubMonitor(
        cloudExtensions.googleDisableUsersPubSubDAO,
        cloudExtensions.googleServicesConfig.disableUsersPubSubConfig,
        cloudExtensions.googleServicesConfig.serviceAccountCredentialJson,
        new DisableUserMessageReceiver(samApplication.userService)(disableUserIoRuntime)
      ).startAndRegisterTermination()
      _ <- cloudExtensions.onBoot(samApplication)
    } yield ()
}
