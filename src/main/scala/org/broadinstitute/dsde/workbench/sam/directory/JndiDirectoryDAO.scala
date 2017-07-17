package org.broadinstitute.dsde.workbench.sam.directory

import java.util
import javax.naming._
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
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
    val member = "uniqueMember"
    val memberOf = "isMemberOf"
    val email = "mail"
    val givenName = "givenName"
    val sn = "sn"
    val cn = "cn"
    val uid = "uid"
  }

  override def createGroup(group: SamGroup): Future[SamGroup] = withContext { ctx =>
    try {
      val groupContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true)  // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "groupofuniquenames").foreach(oc.add)
          myAttrs.put(oc)

          if (!group.members.isEmpty) {
            val members = new BasicAttribute(Attr.member)
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
    ctx.modifyAttributes(groupDn(groupName), DirContext.REMOVE_ATTRIBUTE, new BasicAttributes(Attr.member, subjectDn(removeMember)))
  }

  override def addGroupMember(groupName: SamGroupName, addMember: SamSubject): Future[Unit] = withContext { ctx =>
    ctx.modifyAttributes(groupDn(groupName), DirContext.ADD_ATTRIBUTE, new BasicAttributes(Attr.member, subjectDn(addMember)))
  }

  override def loadGroup(groupName: SamGroupName): Future[Option[SamGroup]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(groupDn(groupName), Array(Attr.cn, Attr.member))

      val cn = getAttribute[String](attributes, Attr.cn).getOrElse(throw new WorkbenchException(s"${Attr.cn} attribute missing: $groupName"))
      val memberDns = getAttributes[String](attributes, Attr.member).getOrElse(Set.empty).toSet

      Option(SamGroup(SamGroupName(cn), memberDns.map(dnToSubject)))

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

          user.email.foreach { email =>
            myAttrs.put(new BasicAttribute(Attr.email, email.value))
          }

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

      val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing: $userId"))
      val emailOption = getAttribute[String](attributes, Attr.email)

      Option(SamUser(SamUserId(uid), emailOption.map(SamUserEmail)))

    }.recover {
      case e: NameNotFoundException => None

    }.get
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

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}


