package org.broadinstitute.dsde.workbench.sam.directory
import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// use ExecutionContexts.blockingThreadPool for blockingEc
class LdapDirectoryDAO(protected val ldapConnectionPool: LDAPConnectionPool, protected val directoryConfig: DirectoryConfig, protected val ecForLdapBlockingIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport with LdapSupport {
  implicit val cs = IO.contextShift(executionContext)

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None): IO[BasicWorkbenchGroup] = {
    val membersAttribute = if (group.members.isEmpty) None else Option(new Attribute(Attr.uniqueMember, group.members.map(subject => subjectDn(subject)).asJava))

    val accessInstructionsAttr = accessInstructionsOpt.collect {
      case accessInstructions => new Attribute(Attr.accessInstructions, accessInstructions)
    }

    val attributes = Seq(
        new Attribute("objectclass", "top", "workbenchGroup"),
        new Attribute(Attr.email, group.email.value),
        new Attribute(Attr.groupUpdatedTimestamp, formattedDate(new Date())),
    ) ++ membersAttribute ++
      accessInstructionsAttr

    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.add(groupDn(group.id), attributes.asJava))).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists"))
    } *> IO.pure(group)
  }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = cs.evalOn(ecForLdapBlockingIO) {
    IO(Option(ldapConnectionPool.getEntry(groupDn(groupName))) map(unmarshalGroup))
  }

  private def unmarshalGroup(results: Entry): BasicWorkbenchGroup = {
    val cn = getAttribute(results, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: ${results.getDN}"))
    val email = getAttribute(results, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: ${results.getDN}"))
    val memberDns = getAttributes(results, Attr.uniqueMember)

    BasicWorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchEmail(email))
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]] = Future {
    val filters = groupNames.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.cn, g.value)).asJava)).toSeq
    ldapSearchStream(groupsOu, SearchScope.SUB, filters:_*)(unmarshalGroup)
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

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Boolean] = Future {
    Try {
      ldapConnectionPool.modify(groupDn(groupId),
        new Modification(ModificationType.ADD, Attr.uniqueMember, subjectDn(addMember)),
        groupUpdatedModification
      )
    } match {
      case Success(_) => true
      case Failure(ldape: LDAPException) if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => false
      case Failure(regrets) => throw regrets
    }
  }

  private def groupUpdatedModification: Modification = {
    new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))
  }

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] = {
    cs.evalOn(ecForLdapBlockingIO)(
        IO(
          ldapConnectionPool
            .modify(groupDn(groupId), new Modification(ModificationType.DELETE, Attr.uniqueMember, subjectDn(removeMember)), groupUpdatedModification)
        ))
      .attempt
      .flatMap {
        case Right(_) => IO.pure(true)
        case Left(ldape: LDAPException) if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE => IO.pure(false)
        case Left(regrets) => IO.raiseError(regrets)
      }
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean] = Future {
    val isMember = for {
      entry <- Option(ldapConnectionPool.getEntry(subjectDn(member), Attr.memberOf))
      memberOf <- Option(entry.getAttribute(Attr.memberOf))
    } yield {
      val memberships = memberOf.getValues.map(_.toLowerCase).toSet //toLowerCase because the dn can have varying capitalization
      memberships.contains(groupDn(groupId).toLowerCase)
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

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): Future[Option[WorkbenchEmail]] = Future {
    Option(ldapConnectionPool.getEntry(groupDn(groupId), Attr.email)).map { entry =>
      Option(entry.getAttributeValue(Attr.email)).map(WorkbenchEmail)
    }.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = {
    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.search(directoryConfig.baseDn, SearchScope.SUB, Filter.createEqualityFilter(Attr.email, email.value)).getSearchEntries.asScala)).map{
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
      userEmails <- users.unsafeToFuture().map(_.map(_.email))
      groupEmails <- groups.map(_.map(_.email))
    } yield (userEmails ++ groupEmails).toSet
  }

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    val attrs = List(
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    ) ++ user.googleSubjectId.map(gsid => List(new Attribute(Attr.googleSubjectId, gsid.value))).getOrElse(List.empty)

    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.add(userDn(user.id), attrs: _*))).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    } *> IO.pure(user)
  }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = cs.evalOn(ecForLdapBlockingIO)(IO(loadUserInternal(userId)))

  private def loadUserInternal(userId: WorkbenchUserId) = {
    Option(ldapConnectionPool.getEntry(userDn(userId))) flatMap { results =>
      unmarshalUser(results).toOption
    }
  }

  private def unmarshalUser(results: Entry): Either[String, WorkbenchUser] =
    for {
      uid <- getAttribute(results, Attr.uid).toRight(s"${Attr.uid} attribute missing")
      email <- getAttribute(results, Attr.email).toRight(s"${Attr.email} attribute missing")
    } yield WorkbenchUser(WorkbenchUserId(uid), getAttribute(results, Attr.googleSubjectId).map(GoogleSubjectId), WorkbenchEmail(email))

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = {
    val filters = userIds.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.uid, g.value)).asJava)).toSeq
    for {
      streamOfEither <- cs.evalOn(ecForLdapBlockingIO)(IO(ldapSearchStream(peopleOu, SearchScope.ONE, filters: _*)(unmarshalUser)))
      res <- streamOfEither.parSequence.fold(err => IO.raiseError(new WorkbenchException(err)), r => IO.pure(r))
    } yield res
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

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] = {
    cs.evalOn(ecForLdapBlockingIO)(IO(ldapSearchStream(directoryConfig.baseDn, SearchScope.SUB, Filter.createEqualityFilter(Attr.uniqueMember, userDn(userId))) { entry =>
      dnToGroupIdentity(entry.getDN)
    }))
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

  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): Future[Set[WorkbenchUserId]] = Future {
    ldapSearchStream(directoryConfig.baseDn, SearchScope.SUB, Filter.createANDFilter(groupIds.map(groupId =>
      Filter.createEqualityFilter(Attr.memberOf, groupDn(groupId))).asJava))(getAttribute(_, Attr.uid)).flatten.map(WorkbenchUserId).toSet
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]] = Future {
    listMemberOfGroups(groupDn(groupId))
  }

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] = cs.evalOn(ecForLdapBlockingIO)(
    IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.ADD, Attr.member, subjectDn(subject)))).void
  ).recoverWith{
    case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
      cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.add(directoryConfig.enabledUsersGroupDn,
        new Attribute("objectclass", Seq("top", "groupofnames").asJava),
        new Attribute(Attr.member, subjectDn(subject))
      ))).void
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => IO.unit
  }

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    try {
      ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, subjectDn(subject)))
    } catch {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE =>
    }
  }

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] = for {
    entry <- cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member)))
  } yield {
    val result = for {
      e <- Option(entry)
      members <- Option(e.getAttributeValues(Attr.member))
    } yield members.contains(subjectDn(subject))
    result.getOrElse(false)
  }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): IO[Option[WorkbenchUser]] = {
    loadSubjectFromGoogleSubjectId(GoogleSubjectId(petSA.value)).map{
      case Some(PetServiceAccountId(userId, _)) => loadUserInternal(userId)
      case _ => None
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val attributes = createPetServiceAccountAttributes(petServiceAccount) ++
      Seq(new Attribute("objectclass", Seq("top", ObjectClass.petServiceAccount).asJava), new Attribute(Attr.project, petServiceAccount.id.project.value))

    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.add(petDn(petServiceAccount.id), attributes:_*))).handleErrorWith{
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${petServiceAccount.id} already exists")))
    } .map(_ => petServiceAccount)
  }

  private def createPetServiceAccountAttributes(petServiceAccount: PetServiceAccount) = {
    val attributes = Seq(
      new Attribute(Attr.email, petServiceAccount.serviceAccount.email.value),
      new Attribute(Attr.sn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.cn, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.googleSubjectId, petServiceAccount.serviceAccount.subjectId.value),
      new Attribute(Attr.uid, petServiceAccount.serviceAccount.subjectId.value)
    )


    val displayNameAttribute = if (!petServiceAccount.serviceAccount.displayName.value.isEmpty) {
      Option(new Attribute(Attr.givenName, petServiceAccount.serviceAccount.displayName.value))
    } else {
      None
    }

    attributes ++ displayNameAttribute
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] = cs.evalOn(ecForLdapBlockingIO) {
    IO(Option(ldapConnectionPool.getEntry(petDn(petServiceAccountId))).map(unmarshalPetServiceAccount))
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

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val modifications = createPetServiceAccountAttributes(petServiceAccount).map { attribute =>
      new Modification(ModificationType.REPLACE, attribute.getName, attribute.getRawValues)
    }
    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.modify(petDn(petServiceAccount.id), modifications.asJava))) *> IO.pure(petServiceAccount)
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): Future[Option[String]] = {
    Option(ldapConnectionPool.getEntry(groupDn(groupName))) match {
      case None => Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupName not found")))
      case Some(e) => Future.successful(Option(e.getAttributeValue(Attr.accessInstructions)))
    }
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] =
    cs.evalOn(ecForLdapBlockingIO)(IO(
      ldapConnectionPool.modify(groupDn(groupName), new Modification(ModificationType.REPLACE, Attr.accessInstructions, accessInstructions))
    ))

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] =
    for{
      entries <- cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.search(peopleOu, SearchScope.SUB, Filter.createEqualityFilter(Attr.googleSubjectId, googleSubjectId.value)).getSearchEntries.asScala))
      res <- entries match {
        case Seq() => IO.pure(None)
        case Seq(subject) => IO.pure(Try(dnToSubject(subject.getDN)).toOption)
        case subjects => IO.raiseError(new WorkbenchException(s"Database error: googleSubjectId $googleSubjectId refers to too many subjects: ${subjects.map(_.getDN)}"))
      }
    } yield res

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] =
    cs.evalOn(ecForLdapBlockingIO)(IO(ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.ADD, Attr.googleSubjectId, googleSubjectId.value))))
}
