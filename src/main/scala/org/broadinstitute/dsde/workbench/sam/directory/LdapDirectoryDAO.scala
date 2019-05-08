package org.broadinstitute.dsde.workbench.sam.directory
import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.effect.{IO, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.{LdapSupport, NewRelicMetrics}
import org.ehcache.Cache

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// use ExecutionContexts.blockingThreadPool for blockingEc
class LdapDirectoryDAO(
    protected val ldapConnectionPool: LDAPConnectionPool,
    protected val directoryConfig: DirectoryConfig,
    protected val ecForLdapBlockingIO: ExecutionContext,
    protected val memberOfCache: Cache[WorkbenchSubject, Set[String]])(implicit executionContext: ExecutionContext, timer: Timer[IO])
    extends DirectoryDAO
    with LazyLogging
    with DirectorySubjectNameSupport
    with LdapSupport {
  implicit val cs = IO.contextShift(executionContext)

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None): IO[BasicWorkbenchGroup] = {
    val membersAttribute =
      if (group.members.isEmpty) None else Option(new Attribute(Attr.uniqueMember, group.members.map(subject => subjectDn(subject)).asJava))

    val accessInstructionsAttr = accessInstructionsOpt.collect {
      case accessInstructions => new Attribute(Attr.accessInstructions, accessInstructions)
    }

    val attributes = Seq(
      new Attribute("objectclass", "top", "workbenchGroup"),
      new Attribute(Attr.email, group.email.value),
      new Attribute(Attr.groupUpdatedTimestamp, formattedDate(new Date())),
    ) ++ membersAttribute ++
      accessInstructionsAttr

    executeLdap(IO(ldapConnectionPool.add(groupDn(group.id), attributes.asJava))).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists"))
    } *> IO.pure(group)
  }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = {
    val res = for {
      entry <- OptionT(executeLdap(IO(ldapConnectionPool.getEntry(groupDn(groupName)))).map(Option.apply))
      r <- OptionT.liftF(IO(unmarshalGroupThrow(entry)))
    } yield r

    res.value
  }

  private def unmarshalGroup(results: Entry): Either[String, BasicWorkbenchGroup] =
    for {
      cn <- getAttribute(results, Attr.cn).toRight(s"${Attr.cn} attribute missing: ${results.getDN}")
      email <- getAttribute(results, Attr.email).toRight(s"${Attr.email} attribute missing: ${results.getDN}")
      memberDns = getAttributes(results, Attr.uniqueMember)
    } yield BasicWorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchEmail(email))

  private def unmarshalGroupThrow(results: Entry): BasicWorkbenchGroup = unmarshalGroup(results).fold(s => throw new WorkbenchException(s), identity)

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = {
    val filters = groupNames.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.cn, g.value)).asJava)).toSeq

    executeLdap(IO(ldapSearchStream(groupsOu, SearchScope.SUB, filters: _*)(unmarshalGroupThrow)))
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]] = {
    val res = for {
      entry <- OptionT[IO, SearchResultEntry]((executeLdap(IO(ldapConnectionPool.getEntry(groupDn(groupName), Attr.email)))).map(Option(_)))
      emailEither = getAttribute(entry, Attr.email).toRight(new WorkbenchException(s"${Attr.email} attribute missing: ${entry.getDN}"))
      email <- OptionT.liftF(IO.fromEither(emailEither).map(WorkbenchEmail))
    } yield email

    res.value
  }

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]] =
    loadGroups(groupNames).map(_.map(g => g.id -> g.email))

  override def deleteGroup(groupName: WorkbenchGroupName): IO[Unit] =
    for {
      ancestors <- listAncestorGroups(groupName)
      res <- if (ancestors.nonEmpty) {
        IO.raiseError(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group")))
      } else {
        executeLdap(IO(ldapConnectionPool.delete(groupDn(groupName))).void)
      }
    } yield res

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean] =
    executeLdap(
      IO(
        ldapConnectionPool
          .modify(groupDn(groupId), new Modification(ModificationType.ADD, Attr.uniqueMember, subjectDn(addMember)), groupUpdatedModification)
      )).attempt
      .flatMap {
        case Right(_) => IO.pure(true)
        case Left(ldape: LDAPException) if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => IO.pure(false)
        case Left(regrets) => IO.raiseError(regrets)
      }

  private def groupUpdatedModification: Modification =
    new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] =
    executeLdap(
      IO(
        ldapConnectionPool
          .modify(groupDn(groupId), new Modification(ModificationType.DELETE, Attr.uniqueMember, subjectDn(removeMember)), groupUpdatedModification)
      )).attempt
      .flatMap {
        case Right(_) => IO.pure(true)
        case Left(ldape: LDAPException) if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE => IO.pure(false)
        case Left(regrets) => IO.raiseError(regrets)
      }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] =
    for {
      memberOf <- ldapLoadMemberOf(member)
    } yield {
      val memberships = memberOf.map(_.toLowerCase) //toLowerCase because the dn can have varying capitalization
      memberships.contains(groupDn(groupId).toLowerCase)
    }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit] = Future {
    ldapConnectionPool.modify(groupDn(groupId), new Modification(ModificationType.REPLACE, Attr.groupSynchronizedTimestamp, formattedDate(new Date())))
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] = {
    executeLdap(IO(Option(ldapConnectionPool.getEntry(groupDn(groupId), Attr.groupSynchronizedTimestamp))
      .map { entry =>
        Option(entry.getAttributeValue(Attr.groupSynchronizedTimestamp)).map(parseDate)
      }
      .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))))
  }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] = {
    executeLdap(IO(Option(ldapConnectionPool.getEntry(groupDn(groupId), Attr.email))
      .map { entry =>
        Option(entry.getAttributeValue(Attr.email)).map(WorkbenchEmail)
      }
      .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))))
  }

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = {
    val ret = for {
      entry <- OptionT(
        executeLdap(IO(ldapConnectionPool.search(directoryConfig.baseDn, SearchScope.SUB, Filter.createEqualityFilter(Attr.email, email.value))))
          .map(Option.apply))
      entries <- OptionT.fromOption[IO](Option(entry.getSearchEntries))
      res <- entries.asScala match {
        case Seq() => OptionT.none[IO, WorkbenchSubject]
        case Seq(subject) => OptionT.liftF(IO(dnToSubject(subject.getDN))) //dnToSubject may throw
        case subjects =>
          OptionT.liftF(
            IO.raiseError[WorkbenchSubject](new WorkbenchException(s"Database error: email $email refers to too many subjects: ${subjects.map(_.getDN)}")))
      }
    } yield res
    NewRelicMetrics.time("loadSubjectFromEmail", ret.value)
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] =
    IO(ldapConnectionPool.getEntry(subjectDn(subject), Attr.email)).map { entry =>
      Option(entry).flatMap(e => Option(e.getAttributeValue(Attr.email))).map(WorkbenchEmail)
    }

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = {
    val users = loadUsers(subjects collect { case userId: WorkbenchUserId => userId })
    val groups = loadGroups(subjects collect { case groupName: WorkbenchGroupName => groupName })
    for {
      userEmails <- users.map(_.map(_.email))
      groupEmails <- groups.map(_.map(_.email))
    } yield userEmails ++ groupEmails
  }

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    val attrs = List(
      new Attribute(Attr.email, user.email.value),
      new Attribute(Attr.sn, user.id.value),
      new Attribute(Attr.cn, user.id.value),
      new Attribute(Attr.uid, user.id.value),
      new Attribute("objectclass", Seq("top", "workbenchPerson").asJava)
    ) ++ user.googleSubjectId.map(gsid => List(new Attribute(Attr.googleSubjectId, gsid.value))).getOrElse(List.empty)

    executeLdap(IO(ldapConnectionPool.add(userDn(user.id), attrs: _*))).adaptError {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    } *> IO.pure(user)
  }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = executeLdap(IO(loadUserInternal(userId)))

  private def loadUserInternal(userId: WorkbenchUserId) =
    Option(ldapConnectionPool.getEntry(userDn(userId))) flatMap { results =>
      unmarshalUser(results).toOption
    }

  private def unmarshalUser(results: Entry): Either[String, WorkbenchUser] =
    for {
      uid <- getAttribute(results, Attr.uid).toRight(s"${Attr.uid} attribute missing")
      email <- getAttribute(results, Attr.email).toRight(s"${Attr.email} attribute missing")
    } yield WorkbenchUser(WorkbenchUserId(uid), getAttribute(results, Attr.googleSubjectId).map(GoogleSubjectId), WorkbenchEmail(email))

  private def unmarshalUserThrow(results: Entry): WorkbenchUser = unmarshalUser(results).fold(s => throw new WorkbenchException(s), identity)

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = {
    val filters = userIds.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.uid, g.value)).asJava)).toSeq
    executeLdap(IO(ldapSearchStream(peopleOu, SearchScope.ONE, filters: _*)(unmarshalUserThrow)))
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

  override def listUsersGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupIdentity]] = listMemberOfGroups(userId)

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] =
    cs.evalOn(ecForLdapBlockingIO)(
      IO(ldapSearchStream(directoryConfig.baseDn, SearchScope.SUB, Filter.createEqualityFilter(Attr.uniqueMember, userDn(userId))) { entry =>
        dnToGroupIdentity(entry.getDN)
      }))

  private def listMemberOfGroups(subject: WorkbenchSubject): IO[Set[WorkbenchGroupIdentity]] =
    for {
      memberOf <- ldapLoadMemberOf(subject)
    } yield {
      memberOf.map(dnToGroupIdentity)
    }

//  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = IO {
//    ldapSearchStream(
//      directoryConfig.baseDn,
//      SearchScope.SUB,
//      Filter.createANDFilter(groupIds.map(groupId => Filter.createEqualityFilter(Attr.memberOf, groupDn(groupId))).asJava)
//    )(getAttribute(_, Attr.uid)).flatten.map(WorkbenchUserId).toSet
//  }

  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = {
    for {
      flatMembers <- groupIds.toList.traverse { groupId =>
        listFlattenedMembers(groupId)
      }
    } yield {
      flatMembers.reduce(_ intersect _)
    }
  }

  def listFlattenedMembers(groupId: WorkbenchGroupIdentity, visitedGroupIds: Set[WorkbenchGroupIdentity] = Set.empty): IO[Set[WorkbenchUserId]] = {
    for {
      directMembers <- listDirectMembers(groupId)
      users = directMembers.collect { case subject: WorkbenchUserId => subject }
      subGroups = directMembers.collect { case subject: WorkbenchGroupIdentity => subject }
      updatedVisitedGroupIds = visitedGroupIds ++ subGroups
      nestedUsers <- (subGroups -- visitedGroupIds).toList.traverse(subGroupId => listFlattenedMembers(subGroupId, updatedVisitedGroupIds))
    } yield {
      users ++ nestedUsers.flatten
    }
  }

  def listDirectMembers(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchSubject]] = {
    executeLdap(
      IO(getAttributes(ldapConnectionPool.getEntry(groupDn(groupId), "UniqueMember"), "UniqueMember").map(dnToSubject))
    )
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchGroupIdentity]] = {
    listMemberOfGroups(groupId)
  }

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] =
    executeLdap(
      IO(ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.ADD, Attr.member, subjectDn(subject)))).void
    ).recoverWith {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
        executeLdap(
          IO(
            ldapConnectionPool.add(
              directoryConfig.enabledUsersGroupDn,
              new Attribute("objectclass", Seq("top", "groupofnames").asJava),
              new Attribute(Attr.member, subjectDn(subject))))).void
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ATTRIBUTE_OR_VALUE_EXISTS => IO.unit
    }

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    try {
      ldapConnectionPool.modify(directoryConfig.enabledUsersGroupDn, new Modification(ModificationType.DELETE, Attr.member, subjectDn(subject)))
    } catch {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_ATTRIBUTE =>
    }
  }

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] =
    for {
      entry <- executeLdap(IO(ldapConnectionPool.getEntry(directoryConfig.enabledUsersGroupDn, Attr.member)))
    } yield {
      val result = for {
        e <- Option(entry)
        members <- Option(e.getAttributeValues(Attr.member))
      } yield members.contains(subjectDn(subject))
      result.getOrElse(false)
    }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): IO[Option[WorkbenchUser]] =
    loadSubjectFromGoogleSubjectId(GoogleSubjectId(petSA.value)).map {
      case Some(PetServiceAccountId(userId, _)) => loadUserInternal(userId)
      case _ => None
    }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val attributes = createPetServiceAccountAttributes(petServiceAccount) ++
      Seq(new Attribute("objectclass", Seq("top", ObjectClass.petServiceAccount).asJava), new Attribute(Attr.project, petServiceAccount.id.project.value))

    executeLdap(IO(ldapConnectionPool.add(petDn(petServiceAccount.id), attributes: _*)))
      .handleErrorWith {
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${petServiceAccount.id} already exists")))
      }
      .map(_ => petServiceAccount)
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

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] = executeLdap {
    IO(Option(ldapConnectionPool.getEntry(petDn(petServiceAccountId))).map(unmarshalPetServiceAccount))
  }

  private def unmarshalPetServiceAccount(entry: Entry): PetServiceAccount = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val displayName = getAttribute(entry, Attr.givenName).getOrElse("")

    PetServiceAccount(
      dnToSubject(entry.getDN).asInstanceOf[PetServiceAccountId],
      ServiceAccount(ServiceAccountSubjectId(uid), WorkbenchEmail(email), ServiceAccountDisplayName(displayName))
    )
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.delete(petDn(petServiceAccountId))))

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = Future {
    ldapSearchStream(userDn(userId), SearchScope.SUB, Filter.createEqualityFilter("objectclass", ObjectClass.petServiceAccount))(unmarshalPetServiceAccount)
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    val modifications = createPetServiceAccountAttributes(petServiceAccount).map { attribute =>
      new Modification(ModificationType.REPLACE, attribute.getName, attribute.getRawValues)
    }
    executeLdap(IO(ldapConnectionPool.modify(petDn(petServiceAccount.id), modifications.asJava))) *> IO.pure(petServiceAccount)
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]] =
    for {
      searchResult <- executeLdap(IO(ldapConnectionPool.getEntry(groupDn(groupName))))
      res <- Option(searchResult) match {
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupName not found")))
        case Some(e) => IO.pure(Option(e.getAttributeValue(Attr.accessInstructions)))
      }
    } yield res

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] =
    executeLdap(
      IO(
        ldapConnectionPool.modify(groupDn(groupName), new Modification(ModificationType.REPLACE, Attr.accessInstructions, accessInstructions))
      ))

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = {
    val res = for {
      searchResult <- executeLdap(
        IO(
          ldapConnectionPool
            .search(peopleOu, SearchScope.SUB, Filter.createEqualityFilter(Attr.googleSubjectId, googleSubjectId.value))))
      r <- Option(searchResult).flatMap(x => Option(x.getSearchEntries).map(_.asScala)) match {
        case None => IO.pure(None)
        case Some(Seq()) => IO.pure(None)
        case Some(Seq(subject)) => IO.pure(Try(dnToSubject(subject.getDN)).toOption)
        case Some(subjects) =>
          IO.raiseError(new WorkbenchException(s"Database error: googleSubjectId $googleSubjectId refers to too many subjects: ${subjects.map(_.getDN)}"))
      }
    } yield r
    NewRelicMetrics.time("loadSubjectFromGoogleSubjectId", res)
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] =
    executeLdap(IO(ldapConnectionPool.modify(userDn(userId), new Modification(ModificationType.ADD, Attr.googleSubjectId, googleSubjectId.value))))
}
