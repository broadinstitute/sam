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
import scala.util.Try
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAO(protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with JndiSupport {
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

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = withContext { ctx =>
    try {
      val policyContext = new BaseDirContext {
        override def getAttributes(name: String): Attributes = {
          val myAttrs = new BasicAttributes(true) // Case ignore

          val oc = new BasicAttribute("objectclass")
          Seq("top", "policy").foreach(oc.add)
          myAttrs.put(oc)
          myAttrs.put(Attr.cn, policy.name.value)
          myAttrs.put(Attr.email, policy.members.email.value) //TODO make sure the google group is created

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

            val memberDns = policy.members.members.map { s =>
              subjectDn(s)
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

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = withContext { ctx =>
    val attributes = ctx.getAttributes(userDn(userId), Array(Attr.memberOf))
    val groupDns = getAttributes[String](attributes, Attr.memberOf).getOrElse(Set.empty).toSet

    val policyDnPattern = dnMatcher(Seq(Attr.policy, Attr.resourceId), resourceTypeDn(resourceTypeName))

    groupDns.collect {
      case policyDnPattern(policyName, resourceId) => ResourceIdAndPolicyName(ResourceId(resourceId), AccessPolicyName(policyName))
    }
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

  override def loadPolicy(policyName: AccessPolicyName, resource: Resource): Future[Option[AccessPolicy]] = withContext { ctx =>
    Try {
      val attributes = ctx.getAttributes(policyDn(policyName, resource))
      Option(unmarshalAccessPolicy(attributes))
    }.recover {
      case e: NameNotFoundException => None
    }.get
  }

  //
  // SUPPORT
  //

  private val resourcesOu = s"ou=resources,${directoryConfig.baseDn}"
  private def resourceTypeDn(resourceTypeName: ResourceTypeName) = s"${Attr.resourceType}=${resourceTypeName.value},$resourcesOu"
  private def resourceDn(resource: Resource) = s"${Attr.resourceId}=${resource.resourceId.value},${resourceTypeDn(resource.resourceTypeName)}"
  private def policyDn(policy: AccessPolicy): String = s"${Attr.policy}=${policy.name},${resourceDn(policy.resource)}"
  private def policyDn(policyName: AccessPolicyName, resource: Resource): String = s"${Attr.policy}=${policyName.value},${resourceDn(resource)}"

  private def getAttributes[T](attributes: Attributes, key: String): Option[TraversableOnce[T]] = {
    Option(attributes.get(key)).map(_.getAll.map(_.asInstanceOf[T]))
  }

  private def unmarshalAccessPolicy(attributes: Attributes): AccessPolicy = {
    val policyName = attributes.get(Attr.policy).get().toString
    val resourceTypeName = ResourceTypeName(attributes.get(Attr.resourceType).get().toString)
    val resourceId = ResourceId(attributes.get(Attr.resourceId).get().toString)
    val resource = Resource(resourceTypeName, resourceId)
    val members = getAttributes[String](attributes, Attr.uniqueMember).getOrElse(Set.empty).toSet.map(dnToSubject)
    val roles = getAttributes[String](attributes, Attr.role).getOrElse(Set.empty).toSet.map(r => ResourceRoleName(r))
    val actions = getAttributes[String](attributes, Attr.action).getOrElse(Set.empty).toSet.map(a => ResourceAction(a))

    val email = WorkbenchGroupEmail(attributes.get(Attr.email).get().toString)
    val name = WorkbenchGroupName(policyName) //TODO

    val group = WorkbenchGroup(name, members, email)

    AccessPolicy(AccessPolicyName(policyName), resource, group, roles, actions)
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
      ctx.search(resourceDn(resource), searchAttrs).map { searchResult =>
        unmarshalAccessPolicy(searchResult.getAttributes)
      }
    } catch {
      case _: NameNotFoundException => Set.empty
    }

    policies.toSet
  }

  private def withContext[T](op: InitialDirContext => T): Future[T] = withContext(directoryConfig.directoryUrl, directoryConfig.user, directoryConfig.password)(op)
}
