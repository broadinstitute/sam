package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.ConnectionType.ConnectionType
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, BasicWorkbenchGroup}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO(val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends DirectoryDAO {
  private val groupSynchronizedDates: mutable.Map[WorkbenchGroupIdentity, Date] = new TrieMap()
  private val users: mutable.Map[WorkbenchUserId, WorkbenchUser] = new TrieMap()
  private val userAttributes: mutable.Map[WorkbenchUserId, mutable.Map[String, Any]] = new TrieMap()
  private val enabledUsers: mutable.Map[WorkbenchSubject, Unit] = new TrieMap()

  private val usersWithEmails: mutable.Map[WorkbenchEmail, WorkbenchUserId] = new TrieMap()
  private val usersWithGoogleSubjectIds: mutable.Map[GoogleSubjectId, WorkbenchSubject] = new TrieMap()
  private val groupsWithEmails: mutable.Map[WorkbenchEmail, WorkbenchGroupName] = new TrieMap()
  private val petServiceAccountsByUser: mutable.Map[PetServiceAccountId, PetServiceAccount] = new TrieMap()
  private val petsWithEmails: mutable.Map[WorkbenchEmail, PetServiceAccountId] = new TrieMap()

  private val groupAccessInstructions: mutable.Map[WorkbenchGroupName, String] = new TrieMap()

  override def getConnectionType(): ConnectionType = ConnectionType.Postgres

  override def createGroup(group: BasicWorkbenchGroup, accessInstruction: Option[String] = None, samRequestContext: SamRequestContext): IO[BasicWorkbenchGroup] =
    if (groups.keySet.contains(group.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists")))
    } else {
      groups += group.id -> group
      groupsWithEmails += group.email -> group.id
      IO.pure(group)
    }

  override def createEnabledUsersGroup(samRequestContext: SamRequestContext): IO[Unit] = IO.unit

  override def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]] = IO {
    groups.get(groupName).map(_.asInstanceOf[BasicWorkbenchGroup])
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[LazyList[BasicWorkbenchGroup]] = IO {
    groups.view.filterKeys(groupNames.map(_.asInstanceOf[WorkbenchGroupIdentity])).values.map(_.asInstanceOf[BasicWorkbenchGroup]).to(LazyList)
  }

  override def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit] = {
    listAncestorGroups(groupName, samRequestContext).map { ancestors =>
      if (ancestors.nonEmpty)
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group"))
      else
        groups -= groupName
    }
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

  override def createUser(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser] =
    if (users.keySet.contains(user.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists")))
    } else {
      users += user.id -> user
      usersWithEmails += user.email -> user.id
      user.googleSubjectId.map(gid => usersWithGoogleSubjectIds += gid -> user.id)

      IO.pure(user)
    }

  override def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = IO {
    users.get(userId)
  }

  override def loadUsers(userIds: Set[WorkbenchUserId], samRequestContext: SamRequestContext): IO[LazyList[WorkbenchUser]] = IO {
    users.view.filterKeys(userIds).values.to(LazyList)
  }

  override def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    users -= userId
  }

  override def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = IO {
    listSubjectsGroups(userId, Set.empty).map(_.id)
  }

  private def listSubjectsGroups(subject: WorkbenchSubject, accumulatedGroups: Set[WorkbenchGroup]): Set[WorkbenchGroup] = {
    val immediateGroups = groups.values.toSet.filter { group => group.members.contains(subject) }

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

  private def listGroupUsers(groupName: WorkbenchGroupIdentity, visitedGroups: Set[WorkbenchGroupIdentity]): Set[WorkbenchUserId] = {
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
  }

  override def listAncestorGroups(groupName: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = IO {
    listSubjectsGroups(groupName, Set.empty).map(_.id)
  }

  override def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] = IO.pure(enabledUsers += ((subject, ())))

  override def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] = IO {
    enabledUsers -= subject
  }

  override def disableAllHumanIdentities(samRequestContext: SamRequestContext): IO[Unit] = IO {
    enabledUsers --= enabledUsers.keys
  }

  override def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = IO {
    enabledUsers.contains(subject)
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] = loadGroup(groupName, samRequestContext).map(_.map(_.email))

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]] = groupNames.to(LazyList).parTraverse { name =>
    loadGroupEmail(name, samRequestContext).map { y => (name -> y.get)}
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
    petServiceAccountsByUser.collect {
      case (PetServiceAccountId(`userId`, _), petSA) => petSA
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

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[LazyList[WorkbenchEmail]] = subjects.to(LazyList).parTraverse { subject =>
      loadSubjectEmail(subject, samRequestContext).map(_.get)
    }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]] = {
    IO.pure(groupSynchronizedDates.get(groupId))
  }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] = {
    IO.pure(groups.get(WorkbenchGroupName(groupId.toString)).map(_.email))
  }

  override def getUserFromPetServiceAccount(petSAId: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = {
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

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]] = {
    if (groups.contains(groupName))
      IO.pure(groupAccessInstructions.get(groupName))
    else
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit] = {
    groupAccessInstructions += groupName -> accessInstructions
    IO.pure(())
  }

  private def addUserAttribute(userId: WorkbenchUserId, attrId: String, value: Any): IO[Unit] = {
    userAttributes.get(userId) match {
      case Some(attributes: Map[String, Any]) => attributes += attrId -> value
      case _ => userAttributes += userId -> (new TrieMap() += attrId -> value)
    }
    IO.unit
  }

  private def readUserAttribute[T](userId: WorkbenchUserId, attrId: String): IO[Option[T]] = {
    val value = for {
      attributes <- userAttributes.get(userId)
      value <- attributes.get(attrId)
    } yield value.asInstanceOf[T]
    IO.pure(value)
  }

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] = {
    val res = for{
      uid <- usersWithGoogleSubjectIds.get(googleSubjectId)
    } yield uid
   res.traverse(IO.pure)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] = {
    users.get(userId).fold[IO[Unit]](IO.pure(new Exception(s"user $userId not found")))(
      u => IO.pure(users.concat(List((userId, u.copy(googleSubjectId = Some(googleSubjectId))))))
    )
  }

  override def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]] = {
    IO.pure(groups.filter { case (_, group) => group.members.contains(userId) }.keys.to(LazyList))
  }

  override def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] = {
    IO(listGroupUsers(groupName, Set.empty))
  }

  override def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext)
      : IO[Option[WorkbenchUser]] = IO.pure(users.values.find(_.azureB2CId.contains(userId)))

  override def setUserAzureB2CId(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    for {
      user <- users.get(userId)
    } yield {
      users += user.id -> user.copy(azureB2CId = Option(b2CId))
    }
  }

  override def checkStatus(samRequestContext: SamRequestContext): Boolean = true
}
