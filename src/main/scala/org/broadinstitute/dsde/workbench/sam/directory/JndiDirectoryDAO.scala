package org.broadinstitute.dsde.workbench.sam.directory

import javax.naming._
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr

/**
 * Created by dvoet on 11/5/15.
 */
class JndiDirectoryDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport with JndiSupport {

  override def createGroup(group: WorkbenchGroup): Future[WorkbenchGroup] = withContext { ctx =>
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

          myAttrs
        }
      }

      ctx.bind(groupDn(group.name), groupContext)
      group

    } catch {
      case e: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.name.value} already exists"))
    }
  }

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = withContext { ctx =>
    ctx.unbind(groupDn(groupName))
  }

  override def removeGroupMember(groupName: WorkbenchGroupName, removeMember: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupName), DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(removeMember)))
  }

  override def addGroupMember(groupName: WorkbenchGroupName, addMember: WorkbenchSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupName), DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(addMember)))
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroup]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName))

      val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: $groupName"))
      val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: $groupName"))
      val memberDns = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet

      Option(WorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchGroupEmail(email)))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroupEmail]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName), Array(Attr.email))

      val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: $groupName"))

      Option(WorkbenchGroupEmail(email))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[WorkbenchGroup]] = batchedLoad(groupNames.toSeq) { batch => { ctx =>
    val filters = batch.toSet[WorkbenchGroupName].map { ref => s"(${Attr.cn}=${ref.value})" }
    ctx.search(groupsOu, s"(|${filters.mkString})", new SearchControls()).extractResultsAndClose.map { result =>
      unmarshalGroup(result.getAttributes)
    }
  } }

  override def createUser(user: WorkbenchUser): Future[WorkbenchUser] = {
    createIdentityInternal(user.id, user.email).map(_ => user)
  }

  override def createPetServiceAccount(petServiceAccount: WorkbenchUserServiceAccount): Future[WorkbenchUserServiceAccount] = {
    createIdentityInternal(petServiceAccount.id, petServiceAccount.email).map(_ => petServiceAccount)
  }

  private def createIdentityInternal(subject: WorkbenchSubject, email: WorkbenchEmail): Future[Unit] = withContext { ctx =>
    try {
      val identityContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "inetOrgPerson").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs.put(new BasicAttribute(Attr.email, email.value))
          myAttrs.put(new BasicAttribute(Attr.sn, subject.value))
          myAttrs.put(new BasicAttribute(Attr.cn, subject.value))
          myAttrs.put(new BasicAttribute(Attr.uid, subject.value))

          myAttrs
        }
      }

      ctx.bind(subjectDn(subject), identityContext)
    } catch {
      case _: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${subject.value} already exists"))
    }
  }

  override def getPetServiceAccountForUser(userId: WorkbenchUserId): Future[Option[WorkbenchUserServiceAccountEmail]] = {
    withContext { ctx =>
      val attributes = ctx.getAttributes(userDn(userId))
      val attr: Option[String] = getAttribute[String](attributes, Attr.petServiceAccount)
      attr.map(WorkbenchUserServiceAccountEmail.apply)
    } recover { case _: NameNotFoundException =>
      None
    }
  }

  override def addPetServiceAccountToUser(userId: WorkbenchUserId, petServiceAccountEmail: WorkbenchUserServiceAccountEmail): Future[WorkbenchUserServiceAccountEmail] = {
    withContext { ctx =>
      val myAttrs = new BasicAttributes(true)
      myAttrs.put(new BasicAttribute("objectclass", "workbenchPerson"))
      myAttrs.put(new BasicAttribute(Attr.petServiceAccount, petServiceAccountEmail.value))

      ctx.modifyAttributes(userDn(userId), DirContext.ADD_ATTRIBUTE, myAttrs)
      petServiceAccountEmail
    } recover { case _: AttributeInUseException =>
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user with id ${userId.value} already has a pet service account"))
    }
  }

  override def removePetServiceAccountFromUser(userId: WorkbenchUserId): Future[Unit] = {
    getPetServiceAccountForUser(userId).flatMap {
      case None => Future.successful(())
      case Some(email) =>
        withContext { ctx =>
          val myAttrs = new BasicAttributes(true)
          myAttrs.put(new BasicAttribute(Attr.petServiceAccount, email.value))

          ctx.modifyAttributes(userDn(userId), DirContext.REMOVE_ATTRIBUTE, myAttrs)
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

  override def loadPetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountId): Future[Option[WorkbenchUserServiceAccount]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(petDn(petServiceAccountId))

      Option(unmarshalPetServiceAccount(attributes))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]] = batchedLoad(userIds.toSeq) { batch => { ctx =>
    val filters = batch.toSet[WorkbenchSubject].map { ref => s"(${Attr.uid}=${ref.value})" }
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

  private def unmarshalUser(attributes: Attributes): WorkbenchUser = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), WorkbenchUserEmail(email))
  }

  private def unmarshalPetServiceAccount(attributes: Attributes): WorkbenchUserServiceAccount = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUserServiceAccount(WorkbenchUserServiceAccountId(uid), WorkbenchUserServiceAccountEmail(email), WorkbenchUserServiceAccountDisplayName(""))
  }

  private def unmarshalGroup(attributes: Attributes): WorkbenchGroup = {
    val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))
    val memberDns = getAttributes[String](attributes, Attr.member).getOrElse(Set.empty).toSet
    val members = memberDns.map(dnToSubject)

    WorkbenchGroup(WorkbenchGroupName(cn), members, WorkbenchGroupEmail(email))
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

  override def deletePetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountId): Future[Unit] = withContext { ctx =>
    ctx.unbind(petDn(petServiceAccountId))
  }

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupName]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(userDn(userId), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupName(attrE.asInstanceOf[String])

    groups.toSet
  }

  override def isGroupMember(groupName: WorkbenchGroupName, member: WorkbenchSubject): Future[Boolean] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(subjectDn(member), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupName(attrE.asInstanceOf[String])

    groups.toSet.contains(groupName)
  }

  override def listFlattenedGroupUsers(groupName: WorkbenchGroupName): Future[Set[WorkbenchUserId]] = withContext { ctx =>
    ctx.search(peopleOu, new BasicAttributes(Attr.memberOf, groupDn(groupName), true)).extractResultsAndClose.map { result =>
      unmarshalUser(result.getAttributes).id
    }.toSet
  }

  override def listAncestorGroups(groupName: WorkbenchGroupName): Future[Set[WorkbenchGroupName]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(groupDn(groupName), Array(Attr.memberOf)).getAll.extractResultsAndClose;
      attrE <- attr.getAll.extractResultsAndClose
    ) yield dnToGroupName(attrE.asInstanceOf[String])

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
