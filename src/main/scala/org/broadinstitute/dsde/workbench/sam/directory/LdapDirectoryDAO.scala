package org.broadinstitute.dsde.workbench.sam.directory
import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class LdapDirectoryDAO(protected val ldapConnectionPool: LDAPConnectionPool, protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport with LdapSupport {

  override def createGroup(group: BasicWorkbenchGroup): Future[BasicWorkbenchGroup] = Future {
    val membersAttribute = if (group.members.isEmpty) None else Option(new Attribute(Attr.uniqueMember, group.members.map(subject => subjectDn(subject)).asJava))
    val attributes = Seq(
      new Attribute("objectclass", "top", "workbenchGroup"),
      new Attribute(Attr.email, group.email.value),
      new Attribute(Attr.groupUpdatedTimestamp, formattedDate(new Date()))
    ) ++ membersAttribute

    ldapConnectionPool.add(groupDn(group.id), attributes.asJava)

    group
  }.recover {
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists"))
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]] = Future {
    Option(ldapConnectionPool.getEntry(groupDn(groupName))) map { results =>
      unmarshalGroup(results)
    }
  }

  private def unmarshalGroup(results: Entry) = {
    val cn = getAttribute(results, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: ${results.getDN}"))
    val email = getAttribute(results, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: ${results.getDN}"))
    val memberDns = getAttributes(results, Attr.uniqueMember).getOrElse(Set.empty).toSet

    BasicWorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchEmail(email))
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]] = Future {
    val filters = groupNames.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.cn, g.value)).asJava)).toSeq
    ldapSearchStream(groupsOu, SearchScope.BASE, filters:_*)(unmarshalGroup)
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] = Future {
    Option(ldapConnectionPool.getEntry(groupDn(groupName), Attr.email)) map { results =>
      WorkbenchEmail(getAttribute(results, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: ${results.getDN}")))
    }
  }

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): Future[Seq[(WorkbenchGroupName, WorkbenchEmail)]] = loadGroups(groupNames).map(_.map(g => g.id -> g.email))

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = {
    listAncestorGroups(groupName).map { ancestors =>
      if (ancestors.nonEmpty) {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group"))
      } else {
        ldapConnectionPool.delete(groupDn(groupName))
      }
    }
  }

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Unit] = Future {
    ldapConnectionPool.modify(groupDn(groupId),
      new Modification(ModificationType.ADD, Attr.uniqueMember, subjectDn(addMember)),
      groupUpdatedModification
    )
  }

  private def groupUpdatedModification = {
    new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))
  }

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Unit] = Future {
    ldapConnectionPool.modify(groupDn(groupId),
      new Modification(ModificationType.DELETE, Attr.uniqueMember, subjectDn(removeMember)),
      groupUpdatedModification
    )
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean] = Future {
    val isMember = for {
      entry <- Option(ldapConnectionPool.getEntry(subjectDn(member), Attr.memberOf))
      memberOf <- Option(entry.getAttribute(Attr.memberOf))
    } yield {
      memberOf.getValues.contains(groupDn(groupId))
    }
    isMember.getOrElse(false)
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit] = Future {
    ldapConnectionPool.modify(groupDn(groupId), new Modification(ModificationType.REPLACE, Attr.groupSynchronizedTimestamp, formattedDate(new Date())))
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = Future {
    Option(ldapConnectionPool.getEntry(groupDn(groupId), Attr.groupSynchronizedTimestamp)).map { entry =>
      Option(entry.getAttributeValue(Attr.groupSynchronizedTimestamp)).map(parseDate)
    }.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail): Future[Option[WorkbenchSubject]] = Future {
    ldapConnectionPool.search(directoryConfig.baseDn, SearchScope.SUB, Filter.createEqualityFilter(Attr.email, email.value)).getSearchEntries.asScala match {
      case Seq() => None
      case Seq(subject) => Option(dnToSubject(subject.getDN))
      case subjects => throw new WorkbenchException(s"Database error: email $email refers to too many subjects: ${subjects.map(_.getDN)}")
    }
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]] = Future {
    Option(ldapConnectionPool.getEntry(subjectDn(subject), Attr.email)).flatMap { entry =>
      Option(entry.getAttributeValue(Attr.email)).map(WorkbenchEmail)
    }
  }

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): Future[Set[WorkbenchEmail]] = {
    val users = loadUsers(subjects collect { case userId: WorkbenchUserId => userId })
    val groups = loadGroups(subjects collect { case groupName: WorkbenchGroupName => groupName })
    for {
      userEmails <- users.map(_.map(_.email))
      groupEmails <- groups.map(_.map(_.email))
    } yield (userEmails ++ groupEmails).toSet
  }

  override def createUser(user: WorkbenchUser): Future[WorkbenchUser] = Future {
    ldapConnectionPool.add(userDn(user.id),
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    )

    user
  }.recover {
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
  }

  override def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]] = Future {
    loadUserInternal(userId)
  }

  private def loadUserInternal(userId: WorkbenchUserId) = {
    Option(ldapConnectionPool.getEntry(userDn(userId))) map { results =>
      unmarshalUser(results)
    }
  }

  private def unmarshalUser(results: Entry): WorkbenchUser = {
    val uid = getAttribute(results, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(results, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), WorkbenchEmail(email))
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]] = Future {
    val filters = userIds.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.uid, g.value)).asJava)).toSeq
    ldapSearchStream(peopleOu, SearchScope.BASE, filters:_*)(unmarshalUser)
  }

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = Future {
    ldapConnectionPool.delete(userDn(userId))
  }

  override def addProxyGroup(userId: WorkbenchUserId, proxyEmail: WorkbenchEmail): Future[Unit] = Future {
    ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.ADD, Attr.proxyEmail, proxyEmail.value))
  }

  override def readProxyGroup(userId: WorkbenchUserId): Future[Option[WorkbenchEmail]] = Future {
    for {
      entry <- Option(ldapConnectionPool.getEntry(userDn(userId), Attr.proxyEmail))
      email <- Option(entry.getAttributeValue(Attr.proxyEmail))
    } yield {
      WorkbenchEmail(email)
    }
  }

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]] = Future {
    listMemberOfGroups(userDn(userId))
  }

  private def listMemberOfGroups(dn: String) = {
    val groupsOption = for {
      entry <- Option(ldapConnectionPool.getEntry(dn, Attr.memberOf))
      memberOf <- Option(entry.getAttributeValues(Attr.memberOf))
    } yield {
      memberOf.toSet.map(dnToGroupIdentity)
    }
    groupsOption.getOrElse(Set.empty)
  }

  override def listFlattenedGroupUsers(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchUserId]] = Future {
    ldapSearchStream(peopleOu, SearchScope.SUB, Filter.createEqualityFilter(Attr.memberOf, groupDn(groupId)))(unmarshalUser).map(_.id).toSet
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]] = Future {
    listMemberOfGroups(groupDn(groupId))
  }

  override def enableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    try {
      ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.ADD, Attr.member, subjectDn(subject)))
    } catch {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
        ldapConnectionPool.add(directoryConfig.enabledUsersGroupDn,
          new Attribute("objectclass", Seq("top", "groupofnames").asJava),
          new Attribute(Attr.member, subjectDn(subject))
        )
    }
  }

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    try {
      ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, subjectDn(subject)))
    } catch {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE =>
    }
  }

  override def isEnabled(subject: WorkbenchSubject): Future[Boolean] = Future {
    val result = for {
      entry <- Option(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member))
      members <- Option(entry.getAttributeValues(Attr.member))
    } yield {
      members.contains(subjectDn(subject))
    }
    result.getOrElse(false)
  }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): Future[Option[WorkbenchUser]] = Future {
    val matchingUsers = ldapSearchStream(peopleOu, SearchScope.SUBORDINATE_SUBTREE, Filter.createEqualityFilter(Attr.uid, petSA.value)) { entry =>
      dnToSubject(entry.getDN)
    }.collect {
      case pet: PetServiceAccountId => pet.userId
    }.toSet

    if (matchingUsers.size > 1) {
      throw new WorkbenchException(s"id $petSA refers to too many subjects: $matchingUsers")
    } else {
      matchingUsers.headOption.flatMap(loadUserInternal)
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = Future {
    val attributes = createPetServiceAccountAttributes(petServiceAccount) ++
      Seq(new Attribute("objectclass", Seq("top", ObjectClass.petServiceAccount).asJava), new Attribute(Attr.project, petServiceAccount.id.project.value))

    ldapConnectionPool.add(petDn(petServiceAccount.id), attributes:_*)
    petServiceAccount
  }.recover {
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${petServiceAccount.id} already exists"))
  }

  private def createPetServiceAccountAttributes(petServiceAccount: PetServiceAccount) = {
    val attributes = Seq(
      new Attribute(Attr.email, petServiceAccount.serviceAccount.email.value),
      new Attribute(Attr.sn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.cn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.uid, petServiceAccount.serviceAccount.subjectId.value)
    )


    val displayNameAttribute = if (!petServiceAccount.serviceAccount.displayName.value.isEmpty) {
      Option(new Attribute(Attr.givenName, petServiceAccount.serviceAccount.displayName.value))
    } else {
      None
    }

    attributes ++ displayNameAttribute
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): Future[Option[PetServiceAccount]] = Future {
    Option(ldapConnectionPool.getEntry(petDn(petServiceAccountId))).map(unmarshalPetServiceAccount)
  }

  private def unmarshalPetServiceAccount(entry: Entry): PetServiceAccount = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val displayName = getAttribute(entry, Attr.givenName).getOrElse("")

    PetServiceAccount(dnToSubject(entry.getDN).asInstanceOf[PetServiceAccountId], ServiceAccount(ServiceAccountSubjectId(uid), WorkbenchEmail(email), ServiceAccountDisplayName(displayName)))
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): Future[Unit] = Future {
    ldapConnectionPool.delete(petDn(petServiceAccountId))
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = Future {
    ldapSearchStream(userDn(userId), SearchScope.SUB, Filter.createEqualityFilter("objectclass", ObjectClass.petServiceAccount))(unmarshalPetServiceAccount)
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = Future {
    val modifications = createPetServiceAccountAttributes(petServiceAccount).map { attribute =>
      new Modification(ModificationType.REPLACE, attribute.getName, attribute.getRawValues)
    }
    ldapConnectionPool.modify(petDn(petServiceAccount.id), modifications.asJava)
    petServiceAccount
  }
}
