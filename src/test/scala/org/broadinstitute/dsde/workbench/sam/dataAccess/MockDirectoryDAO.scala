package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.azure.{ManagedIdentityObjectId, PetManagedIdentity, PetManagedIdentityId}
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, BasicWorkbenchGroup, SamUserTos}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.time.Instant
import java.util.Date
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO(val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap(), passStatusCheck: Boolean = true) extends DirectoryDAO {
  private val groupSynchronizedDates: mutable.Map[WorkbenchGroupIdentity, Date] = new TrieMap()
  private val users: mutable.Map[WorkbenchUserId, SamUser] = new TrieMap()
  private val userTos: mutable.Map[WorkbenchUserId, SamUserTos] = new TrieMap()
  private val userTosHistory: mutable.Map[WorkbenchUserId, List[SamUserTos]] = new TrieMap()
  private val userAttributes: mutable.Map[WorkbenchUserId, SamUserAttributes] = new TrieMap()

  private val usersWithEmails: mutable.Map[WorkbenchEmail, WorkbenchUserId] = new TrieMap()
  private val usersWithGoogleSubjectIds: mutable.Map[GoogleSubjectId, WorkbenchSubject] = new TrieMap()
  private val groupsWithEmails: mutable.Map[WorkbenchEmail, WorkbenchGroupName] = new TrieMap()
  private val petServiceAccountsByUser: mutable.Map[PetServiceAccountId, PetServiceAccount] = new TrieMap()
  private val petsWithEmails: mutable.Map[WorkbenchEmail, PetServiceAccountId] = new TrieMap()

  private val groupAccessInstructions: mutable.Map[WorkbenchGroupName, String] = new TrieMap()

  private val petManagedIdentitiesByUser: mutable.Map[PetManagedIdentityId, PetManagedIdentity] = new TrieMap()

  override def createGroup(
      group: BasicWorkbenchGroup,
      accessInstruction: Option[String] = None,
      samRequestContext: SamRequestContext
  ): IO[BasicWorkbenchGroup] =
    if (groups.keySet.contains(group.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists")))
    } else {
      groups += group.id -> group
      groupsWithEmails += group.email -> group.id
      IO.pure(group)
    }

  override def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]] = IO {
    groups.get(groupName).map(_.asInstanceOf[BasicWorkbenchGroup])
  }

  override def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit] =
    listAncestorGroups(groupName, samRequestContext).map { ancestors =>
      if (ancestors.nonEmpty)
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group")
        )
      else
        groups -= groupName
    }

  override def addGroupMember(groupName: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members + addMember)
      case p: AccessPolicy => p.copy(members = group.members + addMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def removeGroupMember(groupName: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members - removeMember)
      case p: AccessPolicy => p.copy(members = group.members - removeMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    groups.getOrElse(groupId, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members.contains(member)
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] = IO {
    Option(usersWithEmails.getOrElse(email, groupsWithEmails.getOrElse(email, petsWithEmails.getOrElse(email, null))))
  }

  override def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    if (users.keySet.contains(user.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists")))
    } else {
      users += user.id -> user
      usersWithEmails += user.email -> user.id
      user.googleSubjectId.map(gid => usersWithGoogleSubjectIds += gid -> user.id)

      IO.pure(user)
    }

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]] = IO {
    users.get(userId)
  }

  override def loadUsersByQuery(
      userId: Option[WorkbenchUserId],
      googleSubjectId: Option[GoogleSubjectId],
      azureB2CId: Option[AzureB2CId],
      limit: Int,
      samRequestContext: SamRequestContext
  ): IO[Set[SamUser]] =
    IO(users.values.toSet)

  override def updateUserEmail(userId: WorkbenchUserId, email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Unit] = IO {
    // TODO add validation for email
    users.get(userId) match {
      case Some(user) => IO.pure(users += userId -> user.copy(email = email))
      case None => IO.unit
    }
  }

  override def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    users -= userId
  }

  override def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = IO {
    listSubjectsGroups(userId, Set.empty).map(_.id)
  }

  private def listSubjectsGroups(subject: WorkbenchSubject, accumulatedGroups: Set[WorkbenchGroup]): Set[WorkbenchGroup] = {
    val immediateGroups = groups.values.toSet.filter(group => group.members.contains(subject))

    val unvisitedGroups = immediateGroups -- accumulatedGroups
    if (unvisitedGroups.isEmpty) {
      accumulatedGroups
    } else {
      unvisitedGroups.flatMap { group =>
        listSubjectsGroups(group.id, accumulatedGroups ++ immediateGroups)
      }
    }
  }

  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] = IO {
    groupIds.flatMap(groupId => listGroupUsers(groupId, Set.empty))
  }

  private def listGroupUsers(groupName: WorkbenchGroupIdentity, visitedGroups: Set[WorkbenchGroupIdentity]): Set[WorkbenchUserId] =
    if (!visitedGroups.contains(groupName)) {
      val members = groups.getOrElse(groupName, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members

      members.flatMap {
        case userId: WorkbenchUserId => Set(userId)
        case groupName: WorkbenchGroupIdentity => listGroupUsers(groupName, visitedGroups + groupName)
        case petSubjectId: PetServiceAccountId => throw new WorkbenchException(s"Unexpected service account $petSubjectId")
      }
    } else {
      Set.empty
    }

  override def listAncestorGroups(groupName: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = IO {
    listSubjectsGroups(groupName, Set.empty).map(_.id)
  }

  override def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    updateUserEnabled(subject, true)

  override def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    updateUserEnabled(subject, false)

  private def updateUserEnabled(subject: WorkbenchSubject, enabled: Boolean): IO[Unit] =
    subject match {
      case id: WorkbenchUserId =>
        users.get(id) match {
          case Some(user) => IO.pure(users += id -> user.copy(enabled = enabled))
          case None => IO.unit
        }
      case _ => IO.unit
    }

  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    subject match {
      case id: WorkbenchUserId =>
        users.get(id) match {
          case Some(user) => user.enabled
          case None => false
        }
      case _ => false
    }
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    loadGroup(groupName, samRequestContext).map(_.map(_.email))

  override def batchLoadGroupEmail(
      groupNames: Set[WorkbenchGroupName],
      samRequestContext: SamRequestContext
  ): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]] = groupNames.to(LazyList).parTraverse { name =>
    loadGroupEmail(name, samRequestContext).map(y => name -> y.get)
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = {
    if (petServiceAccountsByUser.keySet.contains(petServiceAccount.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet service account ${petServiceAccount.id} already exists")))
    }
    petServiceAccountsByUser += petServiceAccount.id -> petServiceAccount
    petsWithEmails += petServiceAccount.serviceAccount.email -> petServiceAccount.id
    usersWithGoogleSubjectIds += GoogleSubjectId(petServiceAccount.serviceAccount.subjectId.value) -> petServiceAccount.id
    IO.pure(petServiceAccount)
  }

  override def loadPetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]] = IO {
    petServiceAccountsByUser.get(petServiceAccountUniqueId)
  }

  override def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    petServiceAccountsByUser -= petServiceAccountUniqueId
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]] = IO {
    petServiceAccountsByUser.collect { case (PetServiceAccountId(`userId`, _), petSA) =>
      petSA
    }.toSeq
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit] = {
    groupSynchronizedDates += groupId -> new Date()
    IO.unit
  }

  override def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] = IO {
    subject match {
      case id: WorkbenchUserId => users.get(id).map(_.email)
      case id: WorkbenchGroupIdentity => groups.get(id).map(_.email)
      case id: PetServiceAccountId => petServiceAccountsByUser.get(id).map(_.serviceAccount.email)
    }
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]] =
    IO.pure(groupSynchronizedDates.get(groupId))

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    IO.pure(groups.get(WorkbenchGroupName(groupId.toString)).map(_.email))

  override def getUserFromPetServiceAccount(petSAId: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] = {
    val userIds = petServiceAccountsByUser.toSeq.collect {
      case (PetServiceAccountId(userId, _), petSA) if petSA.serviceAccount.subjectId == petSAId => userId
    }
    userIds match {
      case Seq() => IO.pure(None)
      case Seq(userId) => loadUser(userId, samRequestContext)
      case _ => IO.raiseError(new WorkbenchException(s"id $petSAId refers to too many subjects: $userIds"))
    }
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] = IO {
    petServiceAccountsByUser.update(petServiceAccount.id, petServiceAccount)
    petServiceAccount
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]] =
    if (groups.contains(groupName))
      IO.pure(groupAccessInstructions.get(groupName))
    else
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit] = {
    groupAccessInstructions += groupName -> accessInstructions
    IO.pure(())
  }

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] = {
    val res = for {
      uid <- usersWithGoogleSubjectIds.get(googleSubjectId)
    } yield uid
    res.traverse(IO.pure)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] =
    users
      .get(userId)
      .fold[IO[Unit]](IO.pure(new Exception(s"user $userId not found")))(u =>
        IO.pure(users.concat(List((userId, u.copy(googleSubjectId = Some(googleSubjectId))))))
      )

  override def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]] =
    IO.pure(groups.filter { case (_, group) => group.members.contains(userId) }.keys.to(LazyList))

  override def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] =
    IO(listGroupUsers(groupName, Set.empty))

  override def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    IO.pure(users.values.find(_.azureB2CId.contains(userId)))

  override def setUserAzureB2CId(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    for {
      user <- users.get(userId)
    } yield users += user.id -> user.copy(azureB2CId = Option(b2CId))
  }

  override def checkStatus(samRequestContext: SamRequestContext): IO[Boolean] = IO(passStatusCheck)

  override def loadUserByGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    IO.pure(users.values.find(_.googleSubjectId.contains(userId)))

  override def acceptTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] =
    loadUser(userId, samRequestContext).map {
      case None => false
      case Some(user) =>
        users.put(userId, user)
        userTos.put(userId, SamUserTos(userId, tosVersion, TosTable.ACCEPT, Instant.now()))
        val userHistory = userTosHistory.getOrElse(userId, List.empty)
        userTosHistory.put(userId, userHistory :+ SamUserTos(userId, tosVersion, TosTable.ACCEPT, Instant.now()))
        true
    }

  override def rejectTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] =
    loadUser(userId, samRequestContext).map {
      case None => false
      case Some(user) =>
        users.put(userId, user)
        userTos.put(userId, SamUserTos(userId, tosVersion, TosTable.REJECT, Instant.now()))
        val userHistory = userTosHistory.getOrElse(userId, List.empty)
        userTosHistory.put(userId, userHistory :+ SamUserTos(userId, tosVersion, TosTable.REJECT, Instant.now()))
        true
    }

  override def getUserTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserTos]] =
    loadUser(userId, samRequestContext).map {
      case None => None
      case Some(_) =>
        userTos.get(userId)
    }

  override def getUserTermsOfServiceVersion(userId: WorkbenchUserId, tosVersion: Option[String], samRequestContext: SamRequestContext): IO[Option[SamUserTos]] =
    loadUser(userId, samRequestContext).map {
      case None => None
      case Some(_) =>
        tosVersion match {
          case Some(_) => userTos.get(userId)
          case None => None
        }
    }

  override def getUserTermsOfServiceHistory(userId: WorkbenchUserId, samRequestContext: SamRequestContext, limit: Integer): IO[List[SamUserTos]] =
    loadUser(userId, samRequestContext).map {
      case None => List.empty
      case Some(_) => userTosHistory.getOrElse(userId, List.empty)
    }

  override def createPetManagedIdentity(petManagedIdentity: PetManagedIdentity, samRequestContext: SamRequestContext): IO[PetManagedIdentity] = {
    if (petManagedIdentitiesByUser.keySet.contains(petManagedIdentity.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet managed identity ${petManagedIdentity.id} already exists")))
    }
    petManagedIdentitiesByUser += petManagedIdentity.id -> petManagedIdentity
    IO.pure(petManagedIdentity)
  }

  override def loadPetManagedIdentity(petManagedIdentityId: PetManagedIdentityId, samRequestContext: SamRequestContext): IO[Option[PetManagedIdentity]] =
    IO.pure(petManagedIdentitiesByUser.get(petManagedIdentityId))

  override def getUserFromPetManagedIdentity(petManagedIdentityObjectId: ManagedIdentityObjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    IO.pure(None)

  override def setUserRegisteredAt(userId: WorkbenchUserId, registeredAt: Instant, samRequestContext: SamRequestContext): IO[Unit] =
    loadUser(userId, samRequestContext).map {
      case None =>
        throw new WorkbenchException(
          s"Cannot update registeredAt for user ${userId} because registeredAt date has already been set for this user"
        )
      case Some(user) =>
        if (user.registeredAt.isEmpty) {
          users.put(userId, user.copy(registeredAt = Some(registeredAt)))
        } else {
          throw new WorkbenchException(
            s"Cannot update registeredAt for user ${userId} because user does not exist"
          )
        }
    }

  override def getUserAttributes(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserAttributes]] =
    loadUser(userId, samRequestContext).map {
      case None => None
      case Some(_) =>
        userAttributes.get(userId)
    }

  override def setUserAttributes(samUserAttributes: SamUserAttributes, samRequestContext: SamRequestContext): IO[Unit] =
    loadUser(samUserAttributes.userId, samRequestContext).map {
      case None => throw new WorkbenchException("No user found")
      case Some(_) =>
        userAttributes.update(samUserAttributes.userId, samUserAttributes)
    }
}
