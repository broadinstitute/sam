package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO}
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.gax.rpc.AlreadyExistsException
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.protobuf.{Duration, Timestamp}
import com.google.rpc.Code
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
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
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
import scala.concurrent.duration._
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
    extends LazyLogging
    with FutureSupport
    with CloudExtensions
    with Retry {

  private val maxGroupEmailLength = 64

  private[google] def toProxyFromUser(userId: WorkbenchUserId): WorkbenchEmail =
    WorkbenchEmail(s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}PROXY_${userId.value}@${googleServicesConfig.appsDomain}")

  override val emailDomain = googleServicesConfig.appsDomain

  private[google] val allUsersGroupEmail = WorkbenchEmail(
    s"${googleServicesConfig.resourceNamePrefix.getOrElse("")}GROUP_${CloudExtensions.allUsersGroupName.value}@$emailDomain"
  )

  private val userProjectQueryParam = "userProject"
  private val requestedByQueryParam = "requestedBy"

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
                false,
                None
              )
              samApplication.userService.createUser(newUser, samRequestContext).map(_ => newUser)
          }
      }

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

    } yield ()
  }

  override def getUserStatus(user: SamUser): IO[Boolean] =
    getUserProxy(user.id).flatMap {
      case Some(proxyEmail) => IO.fromFuture(IO(googleDirectoryDAO.isGroupMember(proxyEmail, WorkbenchEmail(user.email.value))))
      case None => IO.pure(false)
    }

  /** Evaluate a future for each pet in parallel.
    */
  private def forAllPets[T](userId: WorkbenchUserId, samRequestContext: SamRequestContext)(f: PetServiceAccount => IO[T]): IO[Seq[T]] =
    for {
      pets <- directoryDAO.getAllPetServiceAccountsForUser(userId, samRequestContext)
      a <- pets.traverse { pet =>
        f(pet)
      }
    } yield a

  override def onUserEnable(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- withProxyEmail(user.id) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(proxyEmail, WorkbenchEmail(user.email.value))))
      }
      _ <- forAllPets(user.id, samRequestContext)((petServiceAccount: PetServiceAccount) => enablePetServiceAccount(petServiceAccount, samRequestContext))
    } yield ()

  override def onUserDisable(user: SamUser, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- forAllPets(user.id, samRequestContext)((petServiceAccount: PetServiceAccount) => disablePetServiceAccount(petServiceAccount, samRequestContext))
      _ <- withProxyEmail(user.id) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.removeMemberFromGroup(proxyEmail, WorkbenchEmail(user.email.value))))
      }
    } yield ()

  override def onUserDelete(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- forAllPets(userId, samRequestContext)((petServiceAccount: PetServiceAccount) => removePetServiceAccount(petServiceAccount, samRequestContext))
      _ <- withProxyEmail(userId)(email => IO.fromFuture(IO(googleDirectoryDAO.deleteGroup(email))))
    } yield ()

  override def onGroupDelete(groupEmail: WorkbenchEmail): IO[Unit] =
    IO.fromFuture(IO(googleDirectoryDAO.deleteGroup(groupEmail)))

  def deleteUserPetServiceAccount(userId: WorkbenchUserId, project: GoogleProject, samRequestContext: SamRequestContext): IO[Boolean] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
      deletedSomething <- maybePet match {
        case Some(pet) => removePetServiceAccount(pet, samRequestContext).map(_ => true)
        case None => IO.pure(false) // didn't find the pet, nothing to delete
      }
    } yield deletedSomething

  def createUserPetServiceAccount(user: SamUser, project: GoogleProject, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    val (petSaName, petSaDisplayName) = toPetSAFromUser(user)
    // The normal situation is that the pet either exists in both the database and google or neither.
    // Sometimes, especially in tests, the pet may be removed from the database, but not google or the other way around.
    // This code is a little extra complicated to detect the cases when a pet does not exist in google, the database or
    // both and do the right thing.
    val createPet = for {
      (maybePet, maybeServiceAccount) <- retrievePetAndSA(user.id, petSaName, project, samRequestContext)
      serviceAccount <- maybeServiceAccount match {
        // SA does not exist in google, create it and add it to the proxy group
        case None =>
          for {
            _ <- assertProjectInTerraOrg(project)
            sa <- createPetServiceAccount(project, petSaName, petSaDisplayName)
            _ <- withProxyEmail(user.id) { proxyEmail =>
              // Add group member by uniqueId instead of email to avoid race condition
              // See: https://broadworkbench.atlassian.net/browse/CA-1005
              IO.fromFuture(IO(googleDirectoryDAO.addServiceAccountToGroup(proxyEmail, sa)))
            }
            _ <- IO.fromFuture(IO(googleIamDAO.addServiceAccountUserRoleForUser(project, sa.email, sa.email)))
          } yield sa
        // SA already exists in google, use it
        case Some(sa) => IO.pure(sa)
      }
      pet <- (maybePet, maybeServiceAccount) match {
        // pet does not exist in the database, create it and enable the identity
        case (None, _) =>
          for {
            p <- directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, project), serviceAccount), samRequestContext)
            _ <- directoryDAO.enableIdentity(p.id, samRequestContext)
          } yield p
        // pet already exists in the database, but a new SA was created so update the database with new SA info
        case (Some(p), None) =>
          for {
            p <- directoryDAO.updatePetServiceAccount(p.copy(serviceAccount = serviceAccount), samRequestContext)
          } yield p

        // everything already existed
        case (Some(p), Some(_)) => IO.pure(p)
      }
    } yield pet

    val lock = LockDetails(s"${project.value}-createPet", user.id.value, 30 seconds)

    for {
      (pet, sa) <- retrievePetAndSA(user.id, petSaName, project, samRequestContext) // I'm loving better-monadic-for
      shouldLock = !(pet.isDefined && sa.isDefined) // if either is not defined, we need to lock and potentially create them; else we return the pet
      p <- if (shouldLock) distributedLock.withLock(lock).use(_ => createPet) else pet.get.pure[IO]
    } yield p
  }

  private def createPetServiceAccount(project: GoogleProject, petSaName: ServiceAccountName, petSaDisplayName: ServiceAccountDisplayName) = {
    val accountFuture: Future[ServiceAccount] = googleIamDAO.createServiceAccount(project, petSaName, petSaDisplayName)
    val recovered = accountFuture.recoverWith({
      case throwable: Throwable =>
        if (throwable.getMessage.contains("Service accounts per project")) {
          val errorMessage = "Service account quota reached for this project. Please contact Customer Support."
          val errorReport: ErrorReport = ErrorReport(StatusCodes.InternalServerError, errorMessage, throwable)
          Future.failed(new WorkbenchExceptionWithErrorReport(errorReport))
        }
        Future.failed(throwable)
    })
    IO.fromFuture(IO(recovered))
  }

  private def assertProjectInTerraOrg(project: GoogleProject): IO[Unit] = {
    val validOrg = IO
      .fromFuture(IO(googleProjectDAO.getAncestry(project.value).map { ancestry =>
        ancestry.exists { ancestor =>
          ancestor.getResourceId.getType == GoogleResourceTypes.Organization.value && ancestor.getResourceId.getId == googleServicesConfig.terraGoogleOrgNumber
        }
      }))
      .recoverWith {
        // if the getAncestry call results in a 403 error the project can't be in the right org
        case e: HttpResponseException if e.getStatusCode == StatusCodes.Forbidden.intValue =>
          IO.raiseError(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, s"Access denied from google accessing project ${project.value}, is it a Terra project?", e)
            )
          )
      }

    validOrg.flatMap {
      case true => IO.unit
      case false =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Project ${project.value} must be in Terra Organization")))
    }
  }

  private def retrievePetAndSA(
      userId: WorkbenchUserId,
      petServiceAccountName: ServiceAccountName,
      project: GoogleProject,
      samRequestContext: SamRequestContext
  ): IO[(Option[PetServiceAccount], Option[ServiceAccount])] = {
    val serviceAccount = IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, petServiceAccountName)))
    val pet = directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
    (pet, serviceAccount).parTupled
  }

  def getPetServiceAccountKey(userEmail: WorkbenchEmail, project: GoogleProject, samRequestContext: SamRequestContext): IO[Option[String]] =
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext)
      key <- subject match {
        case Some(userId: WorkbenchUserId) =>
          getPetServiceAccountKey(SamUser(userId, None, userEmail, None, false, None), project, samRequestContext).map(Option(_))
        case _ => IO.pure(None)
      }
    } yield key

  def getPetServiceAccountKey(user: SamUser, project: GoogleProject, samRequestContext: SamRequestContext): IO[String] =
    for {
      pet <- createUserPetServiceAccount(user, project, samRequestContext)
      key <- googleKeyCache.getKey(pet)
    } yield key

  def getPetServiceAccountToken(user: SamUser, project: GoogleProject, scopes: Set[String], samRequestContext: SamRequestContext): Future[String] =
    getPetServiceAccountKey(user, project, samRequestContext).unsafeToFuture().flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  def getArbitraryPetServiceAccountKey(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[String]] =
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext)
      key <- subject match {
        case Some(userId: WorkbenchUserId) =>
          IO.fromFuture(IO(getArbitraryPetServiceAccountKey(SamUser(userId, None, userEmail, None, false, None), samRequestContext))).map(Option(_))
        case _ => IO.none
      }
    } yield key

  def getArbitraryPetServiceAccountKey(user: SamUser, samRequestContext: SamRequestContext): Future[String] =
    getDefaultServiceAccountForShellProject(user, samRequestContext)

  def getArbitraryPetServiceAccountToken(user: SamUser, scopes: Set[String], samRequestContext: SamRequestContext): Future[String] =
    getArbitraryPetServiceAccountKey(user, samRequestContext).flatMap { key =>
      getAccessTokenUsingJson(key, scopes)
    }

  private def getDefaultServiceAccountForShellProject(user: SamUser, samRequestContext: SamRequestContext): Future[String] = {
    val projectName =
      s"fc-${googleServicesConfig.environment.substring(0, Math.min(googleServicesConfig.environment.length(), 5))}-${user.id.value}" // max 30 characters. subject ID is 21
    for {
      creationOperationId <- googleProjectDAO
        .createProject(projectName, googleServicesConfig.terraGoogleOrgNumber, GoogleResourceTypes.Organization)
        .map(opId => Option(opId)) recover {
        case gjre: GoogleJsonResponseException if gjre.getDetails.getCode == StatusCodes.Conflict.intValue => None
      }
      _ <- creationOperationId match {
        case Some(opId) => pollShellProjectCreation(opId) // poll until it's created
        case None => Future.successful(())
      }
      key <- getPetServiceAccountKey(user, GoogleProject(projectName), samRequestContext).unsafeToFuture()
    } yield key
  }

  private def pollShellProjectCreation(operationId: String): Future[Boolean] = {
    def whenCreating(throwable: Throwable): Boolean =
      throwable match {
        case t: WorkbenchException => throw t
        case t: Exception => true
        case _ => false
      }

    retryExponentially(whenCreating) { () =>
      googleProjectDAO.pollOperation(operationId).map { operation =>
        if (operation.getDone && Option(operation.getError).exists(_.getCode.intValue() == Code.ALREADY_EXISTS.getNumber)) true
        else if (operation.getDone && Option(operation.getError).isEmpty) true
        else if (operation.getDone && Option(operation.getError).isDefined)
          throw new WorkbenchException(s"project creation failed with error ${operation.getError.getMessage}")
        else throw new Exception("project still creating...")
      }
    }
  }

  def getAccessTokenUsingJson(saKey: String, desiredScopes: Set[String]): Future[String] = Future {
    val keyStream = new ByteArrayInputStream(saKey.getBytes)
    val credential = ServiceAccountCredentials.fromStream(keyStream).createScoped(desiredScopes.asJava)
    credential.refreshAccessToken.getTokenValue
  }

  def removePetServiceAccountKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project), samRequestContext)
      result <- maybePet match {
        case Some(pet) => googleKeyCache.removeKey(pet, keyId)
        case None => IO.unit
      }
    } yield result

  private def enablePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- directoryDAO.enableIdentity(petServiceAccount.id, samRequestContext)
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(proxyEmail, petServiceAccount.serviceAccount.email)))
      }
    } yield ()

  private def disablePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- directoryDAO.disableIdentity(petServiceAccount.id, samRequestContext)
      _ <- withProxyEmail(petServiceAccount.id.userId) { proxyEmail =>
        IO.fromFuture(IO(googleDirectoryDAO.removeMemberFromGroup(proxyEmail, petServiceAccount.serviceAccount.email)))
      }
    } yield ()

  private def removePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      // disable the pet service account
      _ <- disablePetServiceAccount(petServiceAccount, samRequestContext)
      // remove the record for the pet service account
      _ <- directoryDAO.deletePetServiceAccount(petServiceAccount.id, samRequestContext)
      // remove the service account itself in Google
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccount(petServiceAccount.id.project, toAccountName(petServiceAccount.serviceAccount.email))))
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

  private[google] def toPetSAFromUser(user: SamUser): (ServiceAccountName, ServiceAccountDisplayName) = {
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

  override def getUserProxy(userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext).flatMap {
      case Some(user: WorkbenchUserId) => getUserProxy(user)
      case Some(pet: PetServiceAccountId) => getUserProxy(pet.userId)
      case _ => IO.pure(None)
    }

  private[google] def getUserProxy(userId: WorkbenchUserId): IO[Option[WorkbenchEmail]] =
    IO.pure(Some(toProxyFromUser(userId)))

  private def withProxyEmail[T](userId: WorkbenchUserId)(f: WorkbenchEmail => IO[T]): IO[T] =
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
      petServiceAccount <- createUserPetServiceAccount(samUser, project, samRequestContext)
      petKey <- googleKeyCache.getKey(petServiceAccount)
      serviceAccountCredentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(petKey.getBytes()))
      timeInMinutes = duration.getOrElse(60L)
      queryParams = urlParamsMap + (requestedByQueryParam -> samUser.email.value)
      url <- googleStorageService
        .getSignedBlobUrl(
          bucket,
          name,
          serviceAccountCredentials,
          expirationTime = timeInMinutes,
          expirationTimeUnit = TimeUnit.MINUTES,
          queryParams = queryParams
        )
        .compile
        .lastOrError
    } yield url
  }

  override val allSubSystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.GooglePubSub, Subsystems.GoogleIam)
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
