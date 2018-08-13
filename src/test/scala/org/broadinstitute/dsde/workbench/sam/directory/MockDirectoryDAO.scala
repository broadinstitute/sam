package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountSubjectId}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, BasicWorkbenchGroup}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr

/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO(private val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends DirectoryDAO {
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

  override def createGroup(group: BasicWorkbenchGroup, accessInstruction: Option[String] = None): Future[BasicWorkbenchGroup] = Future {
    if (groups.keySet.contains(group.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists"))
    }
    groups += group.id -> group
    groupsWithEmails += group.email -> group.id
    group
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]] = Future {
    groups.get(groupName).map(_.asInstanceOf[BasicWorkbenchGroup])
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]] = Future {
    groups.filterKeys(groupNames.map(_.asInstanceOf[WorkbenchGroupIdentity])).values.map(_.asInstanceOf[BasicWorkbenchGroup]).toSeq
  }

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = {
    listAncestorGroups(groupName).map { ancestors =>
      if (ancestors.nonEmpty)
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group"))
      else
        groups -= groupName
    }
  }

  override def addGroupMember(groupName: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Boolean] = Future {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members + addMember)
      case p: AccessPolicy => p.copy(members = group.members + addMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def removeGroupMember(groupName: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Boolean] = Future {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members - removeMember)
      case p: AccessPolicy => p.copy(members = group.members - removeMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
    true
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean] = Future {
    groups.getOrElse(groupId, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members.contains(member)
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail): Future[Option[WorkbenchSubject]] = Future {
    Option(usersWithEmails.getOrElse(email, groupsWithEmails.getOrElse(email, petsWithEmails.getOrElse(email, null))))
  }

  override def createUser(user: WorkbenchUser): Future[WorkbenchUser] = Future {
    if (users.keySet.contains(user.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists"))
    }
    users += user.id -> user
    usersWithEmails += user.email -> user.id
    user.googleSubjectId.map(gid => usersWithGoogleSubjectIds += gid -> user.id)

    user
  }

  override def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]] = Future {
    users.get(userId)
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]] = Future {
    users.filterKeys(userIds).values.toSeq
  }

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = Future {
    users -= userId
  }

  override def addProxyGroup(userId: WorkbenchUserId, proxyEmail: WorkbenchEmail): Future[Unit] = addUserAttribute(userId, Attr.proxyEmail, proxyEmail)

  override def readProxyGroup(userId: WorkbenchUserId): Future[Option[WorkbenchEmail]] = readUserAttribute[WorkbenchEmail](userId, Attr.proxyEmail)

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]] = Future {
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

  override def listFlattenedGroupUsers(groupName: WorkbenchGroupIdentity): Future[Set[WorkbenchUserId]] = Future {
    listGroupUsers(groupName, Set.empty)
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

  override def listAncestorGroups(groupName: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]] = Future {
    listSubjectsGroups(groupName, Set.empty).map(_.id)
  }

  override def enableIdentity(subject: WorkbenchSubject): Future[Unit] = Future.successful(enabledUsers += ((subject, ())))

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    enabledUsers -= subject
  }

  override def isEnabled(subject: WorkbenchSubject): Future[Boolean] = Future {
    enabledUsers.contains(subject)
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] = loadGroup(groupName).map(_.map(_.email))

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): Future[Seq[(WorkbenchGroupName, WorkbenchEmail)]] = Future.traverse(groupNames.toSeq) { name =>
    loadGroupEmail(name).map { y =>
      name -> y.get
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = Future {
    if (petServiceAccountsByUser.keySet.contains(petServiceAccount.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet service account ${petServiceAccount.id} already exists"))
    }
    petServiceAccountsByUser += petServiceAccount.id -> petServiceAccount
    petsWithEmails += petServiceAccount.serviceAccount.email -> petServiceAccount.id
    usersWithGoogleSubjectIds += GoogleSubjectId(petServiceAccount.serviceAccount.subjectId.value) -> petServiceAccount.id
    petServiceAccount
  }

  override def loadPetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): Future[Option[PetServiceAccount]] = Future {
    petServiceAccountsByUser.get(petServiceAccountUniqueId)
  }

  override def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): Future[Unit] = Future {
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

  override def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]] = Future {
    subject match {
      case id: WorkbenchUserId => users.get(id).map(_.email)
      case id: WorkbenchGroupIdentity => groups.get(id).map(_.email)
      case id: PetServiceAccountId => petServiceAccountsByUser.get(id).map(_.serviceAccount.email)
    }
  }

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): Future[Set[WorkbenchEmail]] = {
    Future.traverse(subjects) { subject =>
      loadSubjectEmail(subject).map(_.get)
    }
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = {
    Future.successful(groupSynchronizedDates.get(groupId))
  }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): Future[Option[WorkbenchEmail]] = {
    Future.successful(groups.get(WorkbenchGroupName(groupId.toString)).map(_.email))
  }

  override def getUserFromPetServiceAccount(petSAId: ServiceAccountSubjectId): Future[Option[WorkbenchUser]] = {
    val userIds = petServiceAccountsByUser.toSeq.collect {
      case (PetServiceAccountId(userId, _), petSA) if petSA.serviceAccount.subjectId == petSAId => userId
    }
    userIds match {
      case Seq() => Future.successful(None)
      case Seq(userId) => loadUser(userId)
      case _ => Future.failed(new WorkbenchException(s"id $petSAId refers to too many subjects: $userIds"))
    }
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = Future {
    petServiceAccountsByUser.update(petServiceAccount.id, petServiceAccount)
    petServiceAccount
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): Future[Option[String]] = Future {
    if (groups.contains(groupName))
      groupAccessInstructions.get(groupName)
    else
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): Future[Unit] = Future {
    groupAccessInstructions += groupName -> accessInstructions
    Future.successful(())
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

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): Future[Option[WorkbenchSubject]] = {
    val res = for{
      uid <- usersWithGoogleSubjectIds.get(googleSubjectId)
    } yield uid
   res.traverse(Future.successful)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): Future[Unit] = {
    users.get(userId).fold[Future[Unit]](Future.successful(new Exception(s"user $userId not found")))(
      u => Future.successful(users + (userId -> u.copy(googleSubjectId = Some(googleSubjectId))))
    )
  }
}