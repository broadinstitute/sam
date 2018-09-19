package org.broadinstitute.dsde.workbench.sam
package openam

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class LdapAccessPolicyDAO(protected val ldapConnectionPool: LDAPConnectionPool, protected val directoryConfig: DirectoryConfig, protected val resourceTypes: Map[ResourceTypeName, ResourceType])(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DirectorySubjectNameSupport with LdapSupport {
  implicit val cs = IO.contextShift(executionContext)

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

  override def loadResourceAuthDomain(resource: Resource): Future[Set[WorkbenchGroupName]] = loadResourceAuthDomainIO(resource).unsafeToFuture()

  def loadResourceAuthDomainIO(resource: Resource): IO[Set[WorkbenchGroupName]] =
    runBlocking((IO(Option(ldapConnectionPool.getEntry(resourceDn(resource)))))).flatMap {
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$resource auth domain not found")))
        case Some(r) => IO(getAttributes(r, Attr.authDomain).map(WorkbenchGroupName))
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
    val members = getAttributes(entry, Attr.uniqueMember).map(dnToSubject)
    val roles = getAttributes(entry, Attr.role).map(r => ResourceRoleName(r))
    val actions = getAttributes(entry, Attr.action).map(a => ResourceAction(a))

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

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = listAccessPoliciesIO(resourceTypeName, userId).unsafeToFuture()

  private def unmarshalResourceIdAndPolicyName(entry: Entry, resourcesUserIsMember: Set[WorkbenchGroupName]): Either[String, ResourceIdAndPolicyName] = {
    val authDomains = getAttributes(entry, Attr.authDomain).map(WorkbenchGroupName)
    for{
      resourceId <- getAttribute(entry, Attr.resourceId).toRight(s"${entry.getDN} attribute missing: ${Attr.resourceId}. ")
      policyName <- getAttribute(entry, Attr.policy).toRight(s"${entry.getDN} attribute missing: ${Attr.policy}. ")
    } yield ResourceIdAndPolicyName(ResourceId(resourceId), AccessPolicyName(policyName), authDomains, authDomains.filterNot(gn => resourcesUserIsMember.contains(gn)))
  }

  protected[openam] def listAccessPoliciesIO(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = {
    val policyDnPattern = dnMatcher(Seq(Attr.policy, Attr.resourceId), resourceTypeDn(resourceTypeName))
    for{
      rt <- IO.fromEither(resourceTypes.get(resourceTypeName).toRight(new WorkbenchException(s"missing configration for resourceType $resourceTypeName")))
      isConstrained = rt.actionPatterns.exists(_.authDomainConstrainable == true) //required auth domain: there's at least 1 of the Policy's actions is constrained by an Auth Domain
      entry <- runBlocking(IO(ldapConnectionPool.getEntry(userDn(userId), Attr.memberOf)))
      groupDns = getAttributes(entry, Attr.memberOf)
      allResourceIdsUserIsMember = groupDns.collect{case policyDnPattern(_, resourceId) => WorkbenchGroupName(resourceId)}
      filters = allResourceIdsUserIsMember.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(rid => Filter.createEqualityFilter(Attr.resourceId, rid.value)).asJava)).toSeq
      stream <- runBlocking(IO(ldapSearchStream(resourcesOu, SearchScope.SUB, filters:_*)(et => unmarshalResourceIdAndPolicyName(et, allResourceIdsUserIsMember))))
      res <- IO.fromEither(stream.toList.parSequence.leftMap(s => new WorkbenchException(s)))
    } yield res.toSet
  }

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = Future {
    ldapSearchStream(resourceDn(resource), SearchScope.SUB, Filter.createEqualityFilter("objectclass", ObjectClass.policy))(unmarshalAccessPolicy).toSet
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = Future {
    val result = for {
      entry <- Option(ldapConnectionPool.getEntry(subjectDn(user), Attr.memberOf))
      members = getAttributes(entry, Attr.memberOf)
      memberOfDns <- if(members.isEmpty) None else Some(members)
    } yield {
      val memberOfSubjects = memberOfDns.map(dnToSubject)
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

    WorkbenchUser(WorkbenchUserId(uid), getAttribute(entry, Attr.googleSubjectId).map(GoogleSubjectId), WorkbenchEmail(email))
  }

  def runBlocking[A](io: IO[A]): IO[A] = IO.shift(BlockingIO) *> io <* IO.shift
}
