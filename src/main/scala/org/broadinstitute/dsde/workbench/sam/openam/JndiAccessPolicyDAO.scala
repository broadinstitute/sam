package org.broadinstitute.dsde.workbench.sam.openam

import javax.naming.{NameAlreadyBoundException, NameNotFoundException}
import javax.naming.directory._

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.{BaseDirContext, JndiSupport}
import org.broadinstitute.dsde.workbench.sam._

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConverters._

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with JndiSupport {

  private object Attr {
    val resourceId = "resourceId"
    val resourceType = "resourceType"
    val subject = "subject"
    val action = "action"
    val role = "role"
    val mail = "mail"
    val ou = "ou"
    val cn = "cn"
    val policy = "policy"
    val uniqueMember = "uniqueMember"
  }

  //
  // RESOURCE TYPES
  //

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "resourceType").foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.ou, "resources")

          myAttrs
        }
      }

      ctx.bind(resourceTypeDn(resourceTypeName), resourceContext)
      resourceTypeName
    } catch {
      case _: NameAlreadyBoundException => resourceTypeName //resource type has already been created, this is OK
    }
  }

  //
  // RESOURCES
  //

  override def createResource(resource: Resource): Future[Resource] = withContext { ctx =>
    try {
      val resourceContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "resource").foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.resourceType, resource.resourceTypeName.value)

          myAttrs
        }
      }

      ctx.bind(resourceDn(resource), resourceContext)
      resource
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    }
  }

  override def deleteResource(resource: Resource): Future[Unit] = withContext { ctx =>
    ctx.unbind(resourceDn(resource))
  }

  //
  // POLICIES
  //

  //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind
  def toEmail(resourceType: String, resourceId: String, policyName: String) = {
    s"policy-$resourceType-$resourceId-$policyName@dev.test.firecloud.org" //todo: pull appsDomain from conf
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    try {
      val policyContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val email = toEmail(policy.resource.resourceTypeName.value, policy.resource.resourceId.value, policy.name)

          val oc = new BasicAttribute("objectclass")
          Seq("top", "policy").foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.cn, policy.name)
          myAttrs.put(Attr.mail, email) //TODO make sure the google group is created

          if (policy.actions.nonEmpty) {
            val actions = new BasicAttribute(Attr.action)
            policy.actions.foreach(action => actions.add(action.value))
            myAttrs.put(actions)
          }

          if (policy.roles.nonEmpty) {
            val roles = new BasicAttribute(Attr.role)
            policy.roles.foreach(role => roles.add(role.value))
            myAttrs.put(roles)
          }

          if(policy.members.members.nonEmpty) {
            val members = new BasicAttribute(Attr.uniqueMember)

            val memberDns = policy.members.members.map {
              case subject: WorkbenchGroupName => groupDn(subject)
              case subject: WorkbenchUserId => userDn(subject)
            }

            memberDns.foreach(members.add)

            myAttrs.put(members)
          }

          myAttrs.put(Attr.resourceType, policy.resource.resourceTypeName.value)
          myAttrs.put(Attr.resourceId, policy.resource.resourceId.value)
          myAttrs
        }
      }

      ctx.bind(policyDn(policy), policyContext)
      policy
    } catch {
      case _: NameAlreadyBoundException => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A policy by this name already exists for this resource"))
    }
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = withContext { ctx =>
    ctx.unbind(policyDn(policy))
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    val myAttrs = new BasicAttributes(true)

    val users = newPolicy.members.members.collect { case userId: WorkbenchUserId => userId }
    val subGroups = newPolicy.members.members.collect { case groupName: WorkbenchGroupName => groupName }

    addMemberAttributes(users, subGroups, myAttrs) { _.put(new BasicAttribute(Attr.uniqueMember)) } //add attribute with no value when no member present

    if (newPolicy.actions.nonEmpty) {
      val actions = new BasicAttribute(Attr.action)
      newPolicy.actions.foreach(action => actions.add(action.value))
      myAttrs.put(actions)
    }

    if (newPolicy.roles.nonEmpty) {
      val roles = new BasicAttribute(Attr.role)
      newPolicy.roles.foreach(role => roles.add(role.value))
      myAttrs.put(roles)
    }

    ctx.modifyAttributes(policyDn(newPolicy), DirContext.REPLACE_ATTRIBUTE, myAttrs)
    newPolicy
  }

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = {
    val searchAttrs = new BasicAttributes(true)  // Case ignore

    listAccessPolicies(resource, searchAttrs)
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = {
    val searchAttrs = new BasicAttributes(true)  // Case ignore
    searchAttrs.put(Attr.uniqueMember, subjectDn(user))

    listAccessPolicies(resource, searchAttrs)
  }

  //
  // SUPPORT
  //

  private val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"
  private def resourceTypeDn(resourceTypeName: ResourceTypeName) = s"${Attr.resourceType}=${resourceTypeName.value},$resourcesOu"
  private def resourceDn(resource: Resource) = s"${Attr.resourceId}=${resource.resourceId.value},${resourceTypeDn(resource.resourceTypeName)}"
  private def policyDn(policy: AccessPolicy): String = s"${Attr.policy}=${policy.name},${resourceDn(policy.resource)}"

  private def getAttributes[T](attributes: Attributes, key: String): Option[TraversableOnce[T]] = {
    Option(attributes.get(key)).map(_.getAll.asScala.map(_.asInstanceOf[T]))
  }

  /**
    * @param emptyValueFn a function called when no members are present
    ** /
    */
  private def addMemberAttributes(users: Set[WorkbenchUserId], subGroups: Set[WorkbenchGroupName], myAttrs: BasicAttributes)(emptyValueFn: BasicAttributes => Unit): Any = {
    val memberDns = users.map(user => userDn(user)) ++ subGroups.map(subGroup => groupDn(subGroup))
    if (memberDns.nonEmpty) {
      val members = new BasicAttribute(Attr.uniqueMember)
      memberDns.foreach(subject => members.add(subject))
      myAttrs.put(members)
    } else {
      emptyValueFn(myAttrs)
    }
  }

  private def listAccessPolicies(resource: Resource, searchAttrs: BasicAttributes): Future[Set[AccessPolicy]] = withContext { ctx =>
    val policies = try {
      ctx.search(resourceDn(resource), searchAttrs).asScala.map { searchResult =>
        val policyName = searchResult.getAttributes.get(Attr.policy).get().toString
        val resourceTypeName = ResourceTypeName(searchResult.getAttributes.get(Attr.resourceType).get().toString)
        val resourceId = ResourceId(searchResult.getAttributes.get(Attr.resourceId).get().toString)
        val resource = Resource(resourceTypeName, resourceId)
        val members = getAttributes[String](searchResult.getAttributes, Attr.uniqueMember).getOrElse(Set.empty).toSet.map(dnToSubject)
        val roles = getAttributes[String](searchResult.getAttributes, Attr.role).getOrElse(Set.empty).toSet.map(r => ResourceRoleName(r))
        val actions = getAttributes[String](searchResult.getAttributes, Attr.action).getOrElse(Set.empty).toSet.map(a => ResourceAction(a))

        val email = WorkbenchGroupEmail(searchResult.getAttributes.get(Attr.mail).get().toString)
        val name = WorkbenchGroupName(policyName) //TODO

        val group = WorkbenchGroup(name, members, email)

        AccessPolicy(policyName, resource, group, roles, actions)
      }
    }catch {
      case _: NameNotFoundException => Set.empty
    }

    policies.toSet
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
