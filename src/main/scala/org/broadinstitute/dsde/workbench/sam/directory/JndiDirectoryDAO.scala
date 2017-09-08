package org.broadinstitute.dsde.workbench.sam.directory

import java.util
import javax.naming._
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.{WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.collection.JavaConverters._

/**
 * Created by dvoet on 11/5/15.
 */
class JndiDirectoryDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport with JndiSupport {

  /** a bunch of attributes used in directory entries */
  private object Attr {
    val uniqueMember = "uniqueMember"
    val member = "member"
    val memberOf = "isMemberOf"
    val email = "mail"
    val givenName = "givenName"
    val sn = "sn"
    val cn = "cn"
    val uid = "uid"
    val groupUpdatedTimestamp = "groupUpdatedTimestamp"
    val groupSynchronizedTimestamp = "groupSynchronizedTimestamp"
  }

  def init(): Future[Unit] = {
    for {
      _ <- removeWorkbenchGroupSchema()
      _ <- createWorkbenchGroupSchema()
    } yield ()
  }

  def removeWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    Try { schema.destroySubcontext("ClassDefinition/workbenchGroup") }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupSynchronizedTimestamp) }
    Try { schema.destroySubcontext("AttributeDefinition/" + Attr.groupUpdatedTimestamp) }
  }

  def createWorkbenchGroupSchema(): Future[Unit] = withContext { ctx =>
    val schema = ctx.getSchema("")

    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.200", Attr.groupUpdatedTimestamp, "time when group was updated", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))
    createAttributeDefinition(schema, "1.3.6.1.4.1.18060.0.4.3.2.201", Attr.groupSynchronizedTimestamp, "time when group was synchronized", true, Option("generalizedTimeMatch"), Option("generalizedTimeOrderingMatch"), Option("1.3.6.1.4.1.1466.115.121.1.24"))

    val attrs = new BasicAttributes(true) // Ignore case
    attrs.put("NUMERICOID", "1.3.6.1.4.1.18060.0.4.3.2.100")
    attrs.put("NAME", "workbenchGroup")
    attrs.put("SUP", "groupofuniquenames")
    attrs.put("STRUCTURAL", "true")

    val must = new BasicAttribute("MUST")
    must.add("objectclass")
    must.add(Attr.email)
    attrs.put(must)

    val may = new BasicAttribute("MAY")
    may.add(Attr.groupUpdatedTimestamp)
    may.add(Attr.groupSynchronizedTimestamp)
    attrs.put(may)


    // Add the new schema object for "fooObjectClass"
    schema.createSubcontext("ClassDefinition/workbenchGroup", attrs)
  }

  override def createGroup(group: SamGroup): Future[SamGroup] = withContext { ctx =>
    try {
      val groupContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "workbenchGroup").foreach(oc.add)
          myAttrs.put(oc)

          myAttrs.put(new BasicAttribute(Attr.email, group.email.value))

          if (!group.members.isEmpty) {
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

  override def deleteGroup(groupName: SamGroupName): Future[Unit] = withContext { ctx =>
    ctx.unbind(groupDn(groupName))
  }

  override def removeGroupMember(groupName: SamGroupName, removeMember: SamSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupName), DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(removeMember)))
  }

  override def addGroupMember(groupName: SamGroupName, addMember: SamSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupName), DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.uniqueMember, subjectDn(addMember)))
  }

  override def loadGroup(groupName: SamGroupName): Future[Option[SamGroup]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName))

      val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: $groupName"))
      val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing: $groupName"))
      val memberDns = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet

      Option(SamGroup(SamGroupName(cn), memberDns.map(dnToSubject), SamGroupEmail(email)))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }


  override def createUser(user: SamUser): Future[SamUser] = withContext { ctx =>
    try {
      val userContext = new BaseDirContext {
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

      ctx.bind(userDn(user.id), userContext)
      user
    } catch {
      case e: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user with id ${user.id.value} already exists"))
    }
  }

  override def loadUser(userId: SamUserId): Future[Option[SamUser]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(userDn(userId))

      Option(unmarshalUser(attributes))

    }.recover {
      case e: NameNotFoundException => None

    }.get
  }

  private def unmarshalUser(attributes: Attributes): SamUser = {
    val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute[String](attributes, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    SamUser(SamUserId(uid), SamUserEmail(email))
  }

  private def getAttribute[T](attributes: Attributes, key: String): Option[T] = {
    Option(attributes.get(key)).map(_.get.asInstanceOf[T])
  }

  private def getAttributes[T](attributes: Attributes, key: String): Option[TraversableOnce[T]] = {
    Option(attributes.get(key)).map(_.getAll.asScala.map(_.asInstanceOf[T]))
  }

  override def deleteUser(userId: SamUserId): Future[Unit] = withContext { ctx =>
    ctx.unbind(userDn(userId))
  }

  override def listUsersGroups(userId: SamUserId): Future[Set[SamGroupName]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(userDn(userId), Array(Attr.memberOf)).getAll.asScala;
      attrE <- attr.getAll.asScala
    ) yield dnToGroupName(attrE.asInstanceOf[String])

    groups.toSet
  }

  override def isGroupMember(groupName: SamGroupName, member: SamSubject): Future[Boolean] = withContext { ctx =>
    val attributes = ctx.getAttributes(groupDn(groupName))
    val memberDns = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet

    memberDns.map(dnToSubject).contains(member)
  }

  override def listFlattenedGroupUsers(groupName: SamGroupName): Future[Set[SamUserId]] = withContext { ctx =>
    ctx.search(peopleOu, new BasicAttributes(Attr.memberOf, groupDn(groupName), true)).asScala.map { result =>
      unmarshalUser(result.getAttributes).id
    }.toSet
  }

  override def listAncestorGroups(groupName: SamGroupName): Future[Set[SamGroupName]] = withContext { ctx =>
    val groups = for (
      attr <- ctx.getAttributes(groupDn(groupName), Array(Attr.memberOf)).getAll.asScala;
      attrE <- attr.getAll.asScala
    ) yield dnToGroupName(attrE.asInstanceOf[String])

    groups.toSet
  }

  override def enableUser(userId: SamUserId): Future[Unit] = withContext { ctx =>
    Try {
      ctx.modifyAttributes(directoryConfig.enabledUsersGroupDn, DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.member, userDn(userId)))
    }.recover {
      case _: AttributeInUseException => ()
    }
  }

  override def disableUser(userId: SamUserId): Future[Unit] = withContext { ctx =>
    Try {
      ctx.modifyAttributes(directoryConfig.enabledUsersGroupDn, DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.member, userDn(userId)))
    }.recover {
      case _: NoSuchAttributeException => ()
    }
  }

  override def isEnabled(userId: SamUserId): Future[Boolean] = withContext { ctx =>
    val attributes = ctx.getAttributes(directoryConfig.enabledUsersGroupDn)
    val memberDns = getAttributes[String](attributes, Attr.member).getOrElse(Set.empty).toSet

    memberDns.map(dnToSubject).contains(userId)
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}


