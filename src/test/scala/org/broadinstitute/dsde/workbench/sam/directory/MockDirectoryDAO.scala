package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, BasicWorkbenchGroup}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO(private val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends DirectoryDAO {
  implicit val cs = IO.contextShift(ExecutionContext.global)

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

  override def createGroup(group: BasicWorkbenchGroup, accessInstruction: Option[String] = None): IO[BasicWorkbenchGroup] =
    if (groups.keySet.contains(group.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists")))
    } else {
      groups += group.id -> group
      groupsWithEmails += group.email -> group.id
      IO.pure(group)
    }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = IO {
    groups.get(groupName).map(_.asInstanceOf[BasicWorkbenchGroup])
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = IO {
    groups.filterKeys(groupNames.map(_.asInstanceOf[WorkbenchGroupIdentity])).values.map(_.asInstanceOf[BasicWorkbenchGroup]).toStream
  }

  override def deleteGroup(groupName: WorkbenchGroupName): IO[Unit] = {
    listAncestorGroups(groupName).map { ancestors =>
      if (ancestors.nonEmpty)
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group"))
      else
        groups -= groupName
    }
  }

  override def addGroupMember(groupName: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean] = IO {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members + addMember)
      case p: AccessPolicy => p.copy(members = group.members + addMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def removeGroupMember(groupName: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] = IO {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members - removeMember)
      case p: AccessPolicy => p.copy(members = group.members - removeMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] = IO {
    groups.getOrElse(groupId, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members.contains(member)
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = IO {
    Option(usersWithEmails.getOrElse(email, groupsWithEmails.getOrElse(email, petsWithEmails.getOrElse(email, null))))
  }

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] =
    if (users.keySet.contains(user.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists")))
    } else {
      users += user.id -> user
      usersWithEmails += user.email -> user.id
      user.googleSubjectId.map(gid => usersWithGoogleSubjectIds += gid -> user.id)

      IO.pure(user)
    }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = IO {
    users.get(userId)
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = IO {
    users.filterKeys(userIds).values.toStream
  }

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = Future {
    users -= userId
  }

  override def addProxyGroup(userId: WorkbenchUserId, proxyEmail: WorkbenchEmail): Future[Unit] = addUserAttribute(userId, Attr.proxyEmail, proxyEmail)

  override def readProxyGroup(userId: WorkbenchUserId): Future[Option[WorkbenchEmail]] = readUserAttribute[WorkbenchEmail](userId, Attr.proxyEmail)

  override def listUsersGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupIdentity]] = IO {
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

  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = IO {
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

  override def listAncestorGroups(groupName: WorkbenchGroupIdentity): IO[Set[WorkbenchGroupIdentity]] = IO {
    listSubjectsGroups(groupName, Set.empty).map(_.id)
  }

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] = IO.pure(enabledUsers += ((subject, ())))

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    enabledUsers -= subject
  }

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] = IO {
    enabledUsers.contains(subject)
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]] = loadGroup(groupName).map(_.map(_.email))

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]] = groupNames.toStream.parTraverse { name =>
    loadGroupEmail(name).map { y => (name -> y.get)}
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    if (petServiceAccountsByUser.keySet.contains(petServiceAccount.id)) {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet service account ${petServiceAccount.id} already exists")))
    }
    petServiceAccountsByUser += petServiceAccount.id -> petServiceAccount
    petsWithEmails += petServiceAccount.serviceAccount.email -> petServiceAccount.id
    usersWithGoogleSubjectIds += GoogleSubjectId(petServiceAccount.serviceAccount.subjectId.value) -> petServiceAccount.id
    IO.pure(petServiceAccount)
  }

  override def loadPetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): IO[Option[PetServiceAccount]] = IO {
    petServiceAccountsByUser.get(petServiceAccountUniqueId)
  }

  override def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): IO[Unit] = IO {
    petServiceAccountsByUser -= petServiceAccountUniqueId
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = Future {
    petServiceAccountsByUser.collect {
      case (PetServiceAccountId(`userId`, _), petSA) => petSA
    }.toSeq
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit] = {
    groupSynchronizedDates += groupId -> new Date()
    Future.successful(())
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] = IO {
    subject match {
      case id: WorkbenchUserId => users.get(id).map(_.email)
      case id: WorkbenchGroupIdentity => groups.get(id).map(_.email)
      case id: PetServiceAccountId => petServiceAccountsByUser.get(id).map(_.serviceAccount.email)
    }
  }

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = subjects.toStream.parTraverse { subject =>
      loadSubjectEmail(subject).map(_.get)
    }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = {
    Future.successful(groupSynchronizedDates.get(groupId))
  }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): Future[Option[WorkbenchEmail]] = {
    Future.successful(groups.get(WorkbenchGroupName(groupId.toString)).map(_.email))
  }

  override def getUserFromPetServiceAccount(petSAId: ServiceAccountSubjectId): IO[Option[WorkbenchUser]] = {
    val userIds = petServiceAccountsByUser.toSeq.collect {
      case (PetServiceAccountId(userId, _), petSA) if petSA.serviceAccount.subjectId == petSAId => userId
    }
    userIds match {
      case Seq() => IO.pure(None)
      case Seq(userId) => loadUser(userId)
      case _ => IO.raiseError(new WorkbenchException(s"id $petSAId refers to too many subjects: $userIds"))
    }
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = IO {
    petServiceAccountsByUser.update(petServiceAccount.id, petServiceAccount)
    petServiceAccount
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]] = {
    if (groups.contains(groupName))
      IO.pure(groupAccessInstructions.get(groupName))
    else
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] = {
    groupAccessInstructions += groupName -> accessInstructions
    IO.pure(())
  }

  private def addUserAttribute(userId: WorkbenchUserId, attrId: String, value: Any): Future[Unit] = {
    userAttributes.get(userId) match {
      case Some(attributes: Map[String, Any]) => attributes += attrId -> value
      case _ => userAttributes += userId -> (new TrieMap() += attrId -> value)
    }
    Future.successful(())
  }

  private def readUserAttribute[T](userId: WorkbenchUserId, attrId: String): Future[Option[T]] = {
    val value = for {
      attributes <- userAttributes.get(userId)
      value <- attributes.get(attrId)
    } yield value.asInstanceOf[T]
    Future.successful(value)
  }

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = {
    val res = for{
      uid <- usersWithGoogleSubjectIds.get(googleSubjectId)
    } yield uid
   res.traverse(IO.pure)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] = {
    users.get(userId).fold[IO[Unit]](IO.pure(new Exception(s"user $userId not found")))(
      u => IO.pure(users + (userId -> u.copy(googleSubjectId = Some(googleSubjectId))))
    )
  }

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] = {
    IO.pure(groups.filter { case (_, group) => group.members.contains(userId) }.keys.toStream)
  }
}