package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date
import javax.naming._
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}

/**
 * Created by dvoet on 11/5/15.
 */
class JndiDirectoryDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport with JndiSupport {

  override def createGroup(group: BasicWorkbenchGroup): Future[BasicWorkbenchGroup] = withContext { ctx =>
    try {
      val groupContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "workbenchGroup").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs.put(new BasicAttribute(Attr.email, group.email.value))

          if (group.members.nonEmpty) {
            val members = new BasicAttribute(Attr.uniqueMember)
            group.members.foreach(subject => members.add(subjectDn(subject)))
            myAttrs.put(members)
          }

          myAttrs.put(new BasicAttribute(Attr.groupUpdatedTimestamp, formattedDate(new Date())))

          myAttrs
        }
      }

      ctx.bind(groupDn(group.id), groupContext)
      group

    } catch {
      case e: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists"))
    }
  }

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = withContext { ctx =>
    ctx.unbind(groupDn(groupName))
  }

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupId), DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(removeMember)))
    updateUpdatedDate(groupId, ctx)
  }

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupId), DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(addMember)))
    updateUpdatedDate(groupId, ctx)
  }

  private def updateUpdatedDate(groupId: WorkbenchGroupIdentity, ctx: InitialDirContext) = {
    ctx.modifyAttributes(groupDn(groupId), DirContext.REPLACE_ATTRIBUTE, new BasicAttributes(Attr.groupUpdatedTimestamp, formattedDate(new Date())))
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupId), DirContext.REPLACE_ATTRIBUTE, new BasicAttributes(Attr.groupSynchronizedTimestamp, formattedDate(new Date()), true))
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupId), Array(Attr.groupSynchronizedTimestamp))
      getAttribute[String](attributes, Attr.groupSynchronizedTimestamp).map(parseDate)
    }.recover {
      case _: NameNotFoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found"))
    }.get
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName))

      val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: $groupName"))
      val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: $groupName"))
      val memberDns = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet

      Option(BasicWorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchEmail(email)))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName), Array(Attr.email))

      val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: $groupName"))

      Option(WorkbenchEmail(email))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]] = batchedLoad(groupNames.toSeq) { batch => { ctx =>
    val filters = batch.toSet[WorkbenchGroupName].map { ref => s"(${Attr.cn}=${ref.value})" }
    ctx.search(groupsOu, s"(|${filters.mkString})", new SearchControls()).extractResultsAndClose.map { result =>
      unmarshalGroup(result.getAttributes)
    }
  } }

  override def createUser(user: WorkbenchUser): Future[WorkbenchUser] = withContext { ctx =>
    try {
      val identityContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "inetOrgPerson").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs.put(new BasicAttribute(Attr.email, user.email.value))
          myAttrs.put(new BasicAttribute(Attr.sn, user.id.value))
          myAttrs.put(new BasicAttribute(Attr.cn, user.id.value))
          myAttrs.put(new BasicAttribute(Attr.uid, user.id.value))

          myAttrs
        }
      }

      ctx.bind(subjectDn(user.id), identityContext)
      user
    } catch {
      case _: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists"))
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = withContext { ctx =>
    try {
      val identityContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "petServiceAccount").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs.put(new BasicAttribute(Attr.email, petServiceAccount.serviceAccount.email.value))
          myAttrs.put(new BasicAttribute(Attr.sn, petServiceAccount.serviceAccount.subjectId.value))
          myAttrs.put(new BasicAttribute(Attr.cn, petServiceAccount.serviceAccount.subjectId.value))
          myAttrs.put(new BasicAttribute(Attr.uid, petServiceAccount.serviceAccount.subjectId.value))
          myAttrs.put(new BasicAttribute(Attr.project, petServiceAccount.id.project.value))

          if (!petServiceAccount.serviceAccount.displayName.value.isEmpty) {
            myAttrs.put(new BasicAttribute(Attr.givenName, petServiceAccount.serviceAccount.displayName.value))
          }

          myAttrs
        }
      }

      ctx.bind(subjectDn(petServiceAccount.id), identityContext)
      petServiceAccount
    } catch {
      case _: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${petServiceAccount.id} already exists"))
    }
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = {
    withContext { ctx =>
      val matchingAttributes = new BasicAttributes("objectclass", ObjectClass.petServiceAccount, true)
      ctx.search(userDn(userId), matchingAttributes).extractResultsAndClose.map { result =>
        unmarshalPetServiceAccount(userId, result.getAttributes)
      }
    }
  }

  override def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(userDn(userId))

      Option(unmarshalUser(attributes))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): Future[Option[PetServiceAccount]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(petDn(petServiceAccountId))

      Option(unmarshalPetServiceAccount(petServiceAccountId.userId, attributes))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]] = batchedLoad(userIds.toSeq) { batch => { ctx =>
    val filters = batch.toSet[WorkbenchSubject with ValueObject].map { ref => s"(${Attr.uid}=${ref.value})" }
    ctx.search(peopleOu, s"(|${filters.mkString})", new SearchControls()).extractResultsAndClose.map { result =>
      unmarshalUser(result.getAttributes)
    }
  } }

  override def loadSubjectFromEmail(email: String): Future[Option[WorkbenchSubject]] = withContext { ctx =>
    val subjectResults = ctx.search(directoryConfig.baseDn, s"(${Attr.email}=${email})", new SearchControls(SearchControls.SUBTREE_SCOPE, 0, 0, null, false, false))
    val subjects = subjectResults.extractResultsAndClose.map { result =>
      dnToSubject(result.getNameInNamespace)
    }

    subjects match {
      case Seq() => None
      case Seq(subject) => Option(subject)
      case _ => throw new WorkbenchException(s"Database error: email $email refers to too many subjects: $subjects")
    }
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]] = withContext { ctx =>
    val subDn = subjectDn(subject)
    Option(ctx.getAttributes(subDn).get(Attr.email)).map { emailAttr =>
      WorkbenchEmail(emailAttr.get.asInstanceOf[String])
    }
  }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): Future[Option[WorkbenchUser]] = {
    val petUserId = withContext { ctx =>
      val subjectResults = ctx.search(peopleOu, s"(${Attr.uid}=${petSA.value})", new SearchControls(SearchControls.SUBTREE_SCOPE, 0, 0, null, false, false))
      val subjects = subjectResults.extractResultsAndClose.map { result =>
        dnToSubject(result.getNameInNamespace)
      }

      subjects match {
        case Seq() => None
        case Seq(PetServiceAccountId(userId, _)) => Option(userId)
        case Seq(subject) => throw new WorkbenchException(s"Service Account $petSA did not return a valid user: $subject")
        case _ => throw new WorkbenchException(s"id $petSA refers to too many subjects: $subjects")
      }
    }
    petUserId.flatMap {
      case Some(userId) => loadUser(userId)
      case None => Future.successful(None)
    }
  }

  private def unmarshalUser(attributes: Attributes): WorkbenchUser = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), WorkbenchEmail(email))
  }

  private def unmarshalPetServiceAccount(userId: WorkbenchUserId, attributes: Attributes): PetServiceAccount = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val project = getAttribute[String](attributes, Attr.project).getOrElse(throw new WorkbenchException(s"${Attr.project} attribute missing"))
    val displayName = getAttribute[String](attributes, Attr.givenName).getOrElse("")

    PetServiceAccount(PetServiceAccountId(userId, GoogleProject(project)), ServiceAccount(ServiceAccountSubjectId(uid), WorkbenchEmail(email), ServiceAccountDisplayName(displayName)))
  }

  private def unmarshalGroup(attributes: Attributes): BasicWorkbenchGroup = {
    val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val memberDns = getAttributes[String](attributes, Attr.member).getOrElse(Set.empty).toSet
    val members = memberDns.map(dnToSubject)

    BasicWorkbenchGroup(WorkbenchGroupName(cn), members, WorkbenchEmail(email))
  }

  private def getAttribute[T](attributes: Attributes, key: String): Option[T] = {
    Option(attributes.get(key)).map(_.get.asInstanceOf[T])
  }

  private def getAttributes[T](attributes: Attributes, key: String): Option[TraversableOnce[T]] = {
    Option(attributes.get(key)).map(_.getAll.extractResultsAndClose.map(_.asInstanceOf[T]))
  }

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = withContext { ctx =>
    ctx.unbind(userDn(userId))
  }

  override def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): Future[Unit] = withContext { ctx =>
    ctx.unbind(petDn(petServiceAccountUniqueId))
  }

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(userDn(userId), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupIdentity(attrE.asInstanceOf[String])

    groups.toSet
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(subjectDn(member), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupIdentity(attrE.asInstanceOf[String])

    groups.toSet.contains(groupId)
  }

  override def listFlattenedGroupUsers(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchUserId]] = withContext { ctx =>
    ctx.search(peopleOu, new BasicAttributes(Attr.memberOf, groupDn(groupId), true)).extractResultsAndClose.map { result =>
      unmarshalUser(result.getAttributes).id
    }.toSet
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(groupDn(groupId), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupIdentity(attrE.asInstanceOf[String])

    groups.toSet
  }

  override def enableIdentity(subject: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    Try {
      ctx.modifyAttributes(directoryConfig.enabledUsersGroupDn, DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.member, subjectDn(subject)))
    }.recover {
      case _: NameNotFoundException =>
        val groupContext = new BaseDirContext {
          override def getAttributes(name: String): Attributes = {
            val myAttrs = new BasicAttributes(true)  // Case ignore

            val oc = new BasicAttribute("objectclass")
            Seq("top", "groupofnames").foreach(oc.add)
            myAttrs.put(oc)

            myAttrs.put(new BasicAttribute(Attr.member, subjectDn(subject)))

            myAttrs
          }
        }

        ctx.bind(directoryConfig.enabledUsersGroupDn, groupContext)

      case _: AttributeInUseException => ()
    }.get
  }

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    Try {
      ctx.modifyAttributes(directoryConfig.enabledUsersGroupDn, DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.member, subjectDn(subject)))
    }.recover {
      case _: NoSuchAttributeException => ()
    }.get
  }

  override def isEnabled(subject: WorkbenchSubject): Future[Boolean] = withContext { ctx =>
    val attributes = ctx.getAttributes(directoryConfig.enabledUsersGroupDn)
    val memberDns = getAttributes[String](attributes, Attr.member).getOrElse(Set.empty).toSet

    memberDns.map(dnToSubject).contains(subject)
  } recover { case e: NameNotFoundException => false }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
  private def batchedLoad[T, R](input: Seq[T])(op: Seq[T] => InitialDirContext => Seq[R]): Future[Seq[R]] = batchedLoad(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(input)(op)
}
