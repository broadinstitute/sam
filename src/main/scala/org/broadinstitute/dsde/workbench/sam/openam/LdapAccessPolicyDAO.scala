package org.broadinstitute.dsde.workbench.sam.openam

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class LdapAccessPolicyDAO(protected val ldapConnectionPool: LDAPConnectionPool, protected val directoryConfig: DirectoryConfig)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with LdapSupport {
  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = Future {
    ldapConnectionPool.add(resourceTypeDn(resourceTypeName),
      new Attribute("objectclass", List("top", ObjectClass.resourceType).asJava),
      new Attribute(Attr.ou, "resources")
    )
    resourceTypeName
  }.recover {
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS => resourceTypeName
  }

  override def createResource(resource: Resource): Future[Resource] = Future {
    val attributes = List(
      new Attribute("objectclass", List("top", ObjectClass.resource).asJava),
      new Attribute(Attr.resourceType, resource.resourceTypeName.value)
    ) ++
      maybeAttribute(Attr.authDomain, resource.authDomain.map(_.value))

    ldapConnectionPool.add(resourceDn(resource), attributes.asJava)
    resource
  }.recover {
    case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
  }

  // TODO: Method is not tested.  To test properly, we'll probably need a loadResource or getResource method
  override def deleteResource(resource: Resource): Future[Unit] = Future {
    ldapConnectionPool.delete(resourceDn(resource))
  }

  override def loadResourceAuthDomain(resource: Resource): Future[Set[WorkbenchGroupName]] = {
    Option(ldapConnectionPool.getEntry(resourceDn(resource)): SearchResultEntry) match {
      case None => Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$resource auth domain not found")))
      case Some(r) => Future.successful(getAttributes(r, Attr.authDomain).getOrElse(Set.empty).toSet.map(g => WorkbenchGroupName(g)))
    }
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    val attributes = List(
      new Attribute("objectclass", "top", ObjectClass.policy),
      new Attribute(Attr.cn, policy.id.accessPolicyName.value),
      new Attribute(Attr.email, policy.email.value),
      new Attribute(Attr.resourceType, policy.id.resource.resourceTypeName.value),
      new Attribute(Attr.resourceId, policy.id.resource.resourceId.value),
      new Attribute(Attr.groupUpdatedTimestamp, formattedDate(new Date()))
    ) ++
      maybeAttribute(Attr.action, policy.actions.map(_.value)) ++
      maybeAttribute(Attr.role, policy.roles.map(_.value)) ++
      maybeAttribute(Attr.uniqueMember, policy.members.map(subjectDn))

    ldapConnectionPool.add(policyDn(policy.id), attributes.asJava)
    policy
  }

  private def maybeAttribute(attr: String, values: Set[String]): Option[Attribute] = values.toSeq match {
    case Seq() => None
    case _ => Option(new Attribute(attr, values.asJava))
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = Future {
    ldapConnectionPool.delete(policyDn(policy.id))
  }

  override def loadPolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicy]] = Future {
    Option(ldapConnectionPool.getEntry(policyDn(resourceAndPolicyName))).map(unmarshalAccessPolicy)
  }

  private def unmarshalAccessPolicy(entry: Entry): AccessPolicy = {
    val policyName = getAttribute(entry, Attr.policy).get
    val resourceTypeName = ResourceTypeName(getAttribute(entry, Attr.resourceType).get)
    val resourceId = ResourceId(getAttribute(entry, Attr.resourceId).get)
    val resource = Resource(resourceTypeName, resourceId)
    val members = getAttributes(entry, Attr.uniqueMember).getOrElse(Set.empty).toSet.map(dnToSubject)
    val roles = getAttributes(entry, Attr.role).getOrElse(Set.empty).toSet.map(r => ResourceRoleName(r))
    val actions = getAttributes(entry, Attr.action).getOrElse(Set.empty).toSet.map(a => ResourceAction(a))

    val email = WorkbenchEmail(getAttribute(entry, Attr.email).get)

    AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName(policyName)), members, email, roles, actions)
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = Future {
    val memberMod = new Modification(ModificationType.REPLACE, Attr.uniqueMember, newPolicy.members.map(subjectDn).toArray:_*)
    val actionMod = new Modification(ModificationType.REPLACE, Attr.action, newPolicy.actions.map(_.value).toArray:_*)
    val roleMod = new Modification(ModificationType.REPLACE, Attr.role, newPolicy.roles.map(_.value).toArray:_*)
    val dateMod = new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))

    ldapConnectionPool.modify(policyDn(newPolicy.id), memberMod, actionMod, roleMod, dateMod)

    newPolicy
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = Future {
    val entry = ldapConnectionPool.getEntry(userDn(userId), Attr.memberOf)
    val groupDns = getAttributes(entry, Attr.memberOf).getOrElse(Set.empty).toSet

    val policyDnPattern = dnMatcher(List(Attr.policy, Attr.resourceId), resourceTypeDn(resourceTypeName))

    groupDns.collect {
      case policyDnPattern(policyName, resourceId) => ResourceIdAndPolicyName(ResourceId(resourceId), AccessPolicyName(policyName))
    }
  }

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = Future {
    ldapSearchStream(resourceDn(resource), SearchScope.SUB, Filter.createEqualityFilter("objectclass", ObjectClass.policy))(unmarshalAccessPolicy).toSet
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = Future {
    val result = for {
      entry <- Option(ldapConnectionPool.getEntry(subjectDn(user), Attr.memberOf))
      memberOfDns <- getAttributes(entry, Attr.memberOf)
    } yield {
      val memberOfSubjects = memberOfDns.map(dnToSubject).toSet
      memberOfSubjects.collect {
        case ripn@ResourceAndPolicyName(`resource`, _) => unmarshalAccessPolicy(ldapConnectionPool.getEntry(subjectDn(ripn)))
      }
    }
    result.getOrElse(Set.empty)
  }

  override def listFlattenedPolicyMembers(resourceAndPolicyName: ResourceAndPolicyName): Future[Set[WorkbenchUserId]] = Future {
    ldapSearchStream(peopleOu, SearchScope.ONE, Filter.createEqualityFilter(Attr.memberOf, policyDn(resourceAndPolicyName)))(unmarshalUser).map(_.id).toSet
  }

  private def unmarshalUser(entry: Entry): WorkbenchUser = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), WorkbenchEmail(email))
  }
}
