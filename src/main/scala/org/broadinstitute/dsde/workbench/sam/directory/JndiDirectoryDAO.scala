package org.broadinstitute.dsde.workbench.sam.directory

import java.util
import javax.naming._
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.sam.{WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}
import scala.collection.JavaConverters._

/**
 * Created by dvoet on 11/5/15.
 */
class JndiDirectoryDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DirectorySubjectNameSupport {

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
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, e.getMessage))
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
          Seq("top", "inetorgperson").foreach(oc.add)
          myAttrs.put(oc)

          user.email.foreach { email =>
            myAttrs.put(new BasicAttribute(Attr.email, email.value))
          }

          myAttrs.put(new BasicAttribute(Attr.sn, user.lastName))
          myAttrs.put(new BasicAttribute(Attr.givenName, user.firstName))
          myAttrs.put(new BasicAttribute(Attr.cn, s"${user.firstName} ${user.lastName}"))

          myAttrs
        }
      }

      ctx.bind(userDn(user.id), userContext)
      user
    } catch {
      case e: NameAlreadyBoundException =>
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, e.getMessage))
    }
  }

  override def loadUser(userId: SamUserId): Future[Option[SamUser]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(userDn(userId))

      val uid = getAttribute[String](attributes, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing: $userId"))
      val emailOption = getAttribute[String](attributes, Attr.email)
      val firstName = getAttribute[String](attributes, Attr.givenName).getOrElse(throw new WorkbenchException(s"${Attr.givenName} attribute missing: $userId"))
      val lastName = getAttribute[String](attributes, Attr.sn).getOrElse(throw new WorkbenchException(s"${Attr.sn} attribute missing: $userId"))

      Option(SamUser(SamUserId(uid), firstName, lastName, emailOption.map(SamUserEmail)))

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

  private def getContext(): InitialDirContext = {
    val env = new util.Hashtable[String, String]()
    env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
    env.put(Context.PROVIDER_URL, directoryConfig.directoryUrl)
    env.put(Context.SECURITY_PRINCIPAL, directoryConfig.user)
    env.put(Context.SECURITY_CREDENTIALS, directoryConfig.password)

    // enable connection pooling
    env.put("com.sun.jndi.ldap.connect.pool", "true")

    new InitialDirContext(env)
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = Future {
    val ctx = getContext()
    val t = Try(op(ctx))
    ctx.close()
    t.get
  }
}


/**
 * this does nothing but throw new OperationNotSupportedException but makes extending classes nice
 */
trait BaseDirContext extends DirContext {
  override def getAttributes(name: Name): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: String): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: Name, attrIds: Array[String]): Attributes = throw new OperationNotSupportedException
  override def getAttributes(name: String, attrIds: Array[String]): Attributes = throw new OperationNotSupportedException
  override def getSchema(name: Name): DirContext = throw new OperationNotSupportedException
  override def getSchema(name: String): DirContext = throw new OperationNotSupportedException
  override def createSubcontext(name: Name, attrs: Attributes): DirContext = throw new OperationNotSupportedException
  override def createSubcontext(name: String, attrs: Attributes): DirContext = throw new OperationNotSupportedException
  override def modifyAttributes(name: Name, mod_op: Int, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: String, mod_op: Int, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: Name, mods: Array[ModificationItem]): Unit = throw new OperationNotSupportedException
  override def modifyAttributes(name: String, mods: Array[ModificationItem]): Unit = throw new OperationNotSupportedException
  override def getSchemaClassDefinition(name: Name): DirContext = throw new OperationNotSupportedException
  override def getSchemaClassDefinition(name: String): DirContext = throw new OperationNotSupportedException
  override def rebind(name: Name, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def rebind(name: String, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def bind(name: Name, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def bind(name: String, obj: scala.Any, attrs: Attributes): Unit = throw new OperationNotSupportedException
  override def search(name: Name, matchingAttributes: Attributes, attributesToReturn: Array[String]): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, matchingAttributes: Attributes, attributesToReturn: Array[String]): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, matchingAttributes: Attributes): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, matchingAttributes: Attributes): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, filter: String, cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, filter: String, cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: Name, filterExpr: String, filterArgs: Array[AnyRef], cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def search(name: String, filterExpr: String, filterArgs: Array[AnyRef], cons: SearchControls): NamingEnumeration[SearchResult] = throw new OperationNotSupportedException
  override def getNameInNamespace: String = throw new OperationNotSupportedException
  override def addToEnvironment(propName: String, propVal: scala.Any): AnyRef = throw new OperationNotSupportedException
  override def rename(oldName: Name, newName: Name): Unit = throw new OperationNotSupportedException
  override def rename(oldName: String, newName: String): Unit = throw new OperationNotSupportedException
  override def lookup(name: Name): AnyRef = throw new OperationNotSupportedException
  override def lookup(name: String): AnyRef = throw new OperationNotSupportedException
  override def destroySubcontext(name: Name): Unit = throw new OperationNotSupportedException
  override def destroySubcontext(name: String): Unit = throw new OperationNotSupportedException
  override def composeName(name: Name, prefix: Name): Name = throw new OperationNotSupportedException
  override def composeName(name: String, prefix: String): String = throw new OperationNotSupportedException
  override def createSubcontext(name: Name): Context = throw new OperationNotSupportedException
  override def createSubcontext(name: String): Context = throw new OperationNotSupportedException
  override def unbind(name: Name): Unit = throw new OperationNotSupportedException
  override def unbind(name: String): Unit = throw new OperationNotSupportedException
  override def removeFromEnvironment(propName: String): AnyRef = throw new OperationNotSupportedException
  override def rebind(name: Name, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def rebind(name: String, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def getEnvironment: util.Hashtable[_, _] = throw new OperationNotSupportedException
  override def list(name: Name): NamingEnumeration[NameClassPair] = throw new OperationNotSupportedException
  override def list(name: String): NamingEnumeration[NameClassPair] = throw new OperationNotSupportedException
  override def close(): Unit = throw new OperationNotSupportedException
  override def lookupLink(name: Name): AnyRef = throw new OperationNotSupportedException
  override def lookupLink(name: String): AnyRef = throw new OperationNotSupportedException
  override def getNameParser(name: Name): NameParser = throw new OperationNotSupportedException
  override def getNameParser(name: String): NameParser = throw new OperationNotSupportedException
  override def bind(name: Name, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def bind(name: String, obj: scala.Any): Unit = throw new OperationNotSupportedException
  override def listBindings(name: Name): NamingEnumeration[Binding] = throw new OperationNotSupportedException
  override def listBindings(name: String): NamingEnumeration[Binding] = throw new OperationNotSupportedException
}