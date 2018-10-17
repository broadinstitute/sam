package org.broadinstitute.dsde.workbench.sam
package openam

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.{Attr, ObjectClass}
import org.broadinstitute.dsde.workbench.sam.util.LdapSupport
import cats.implicits._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

class LdapAccessPolicyDAO(protected val ldapConnectionPool: LDAPConnectionPool, protected val directoryConfig: DirectoryConfig, blockingEc: ExecutionContext)(implicit cs: ContextShift[IO]) extends AccessPolicyDAO with DirectorySubjectNameSupport with LdapSupport {

  override def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName] = {
    for{
      _ <- cs.evalOn(blockingEc)(IO(ldapConnectionPool.add(resourceTypeDn(resourceTypeName),
        new Attribute("objectclass", List("top", ObjectClass.resourceType).asJava),
        new Attribute(Attr.ou, "resources"))
      )).void.recover{
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS => ()
      }
    } yield resourceTypeName
  }

  override def createResource(resource: Resource): IO[Resource] = {
    val attributes = List(
      new Attribute("objectclass", List("top", ObjectClass.resource).asJava),
      new Attribute(Attr.resourceType, resource.resourceTypeName.value)
    ) ++ maybeAttribute(Attr.authDomain, resource.authDomain.map(_.value))

    cs.evalOn(blockingEc)(IO(ldapConnectionPool.add(resourceDn(FullyQualifiedResourceId(resource.resourceTypeName, resource.resourceId)), attributes.asJava))).recoverWith{
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists")))
    }.map(_ => resource)
  }

  // TODO: Method is not tested.  To test properly, we'll probably need a loadResource or getResource method
  override def deleteResource(resource: FullyQualifiedResourceId): IO[Unit] = IO(ldapConnectionPool.delete(resourceDn(resource)))

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[Set[WorkbenchGroupName]] = {
    cs.evalOn(blockingEc)((IO(Option(ldapConnectionPool.getEntry(resourceDn(resource)))))).flatMap {
      case None =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource $resource not found")))
      case Some(r) =>
        IO(getAttributes(r, Attr.authDomain).map(WorkbenchGroupName))
    }
  }

  private def unmarshalResource(results: Entry): Either[String, Resource] = {
    for {
      resourceTypeName <- getAttribute(results, Attr.resourceType).toRight(s"${Attr.resourceType} attribute missing")
      resourceId <- getAttribute(results, Attr.resourceId).toRight(s"${Attr.resourceId} attribute missing")
    } yield {
      val authDomain =  getAttributes(results, Attr.authDomain).map(g => WorkbenchGroupName(g)).toSet
      Resource(ResourceTypeName(resourceTypeName), ResourceId(resourceId), authDomain)
    }
  }

  override def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]] = for {
    res <- cs.evalOn(blockingEc)(IO(ldapSearchStream(resourcesOu, SearchScope.SUB, Filter.createEqualityFilter(Attr.authDomain, groupId.toString))(unmarshalResource)))
    r <- res.parSequence.fold(err => IO.raiseError(new WorkbenchException(err)), r => IO.pure(r.toSet))
  } yield r

  override def createPolicy(policy: AccessPolicy): IO[AccessPolicy] = {
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
      maybeAttribute(Attr.uniqueMember, policy.members.map(subjectDn)) :+
      new Attribute(Attr.public, policy.public.toString)

    cs.evalOn(blockingEc)(IO(ldapConnectionPool.add(policyDn(
      FullyQualifiedPolicyId(
        FullyQualifiedResourceId(policy.id.resource.resourceTypeName, policy.id.resource.resourceId), policy.id.accessPolicyName)), attributes.asJava)).map(_ => policy))
  }

  private def maybeAttribute(attr: String, values: Set[String]): Option[Attribute] = values.toSeq match {
    case Seq() => None
    case _ => Option(new Attribute(attr, values.asJava))
  }

  override def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit] = cs.evalOn(blockingEc)(IO(
    ldapConnectionPool.delete(policyDn(policy))))

  override def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Option[AccessPolicy]] = cs.evalOn(blockingEc) {
    IO(Option(ldapConnectionPool.getEntry(policyDn(resourceAndPolicyName))).map(unmarshalAccessPolicy))
  }

  private def unmarshalAccessPolicy(entry: Entry): AccessPolicy = {
    val policyName = getAttribute(entry, Attr.policy).get
    val resourceTypeName = ResourceTypeName(getAttribute(entry, Attr.resourceType).get)
    val resourceId = ResourceId(getAttribute(entry, Attr.resourceId).get)
    val members = getAttributes(entry, Attr.uniqueMember).map(dnToSubject)
    val roles = getAttributes(entry, Attr.role).map(r => ResourceRoleName(r))
    val actions = getAttributes(entry, Attr.action).map(a => ResourceAction(a))
    val public = Option(entry.getAttribute(Attr.public)).map(_.getValueAsBoolean.booleanValue())

    val email = WorkbenchEmail(getAttribute(entry, Attr.email).get)

    AccessPolicy(
      FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceId), AccessPolicyName(policyName)), members, email, roles, actions, public.contains(true))
  }

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit] = {
    val memberMod = new Modification(ModificationType.REPLACE, Attr.uniqueMember, memberList.map(subjectDn).toArray:_*)
    val dateMod = new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))

    cs.evalOn(blockingEc)(IO(ldapConnectionPool.modify(policyDn(id), memberMod, dateMod)))
  }

  override def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy] = {
    val memberMod = new Modification(ModificationType.REPLACE, Attr.uniqueMember, newPolicy.members.map(subjectDn).toArray:_*)
    val actionMod = new Modification(ModificationType.REPLACE, Attr.action, newPolicy.actions.map(_.value).toArray:_*)
    val roleMod = new Modification(ModificationType.REPLACE, Attr.role, newPolicy.roles.map(_.value).toArray:_*)
    val dateMod = new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))
    val publicMod = new Modification(ModificationType.REPLACE, Attr.public, newPolicy.public.toString)

    val ridPolicyName = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(newPolicy.id.resource.resourceTypeName, newPolicy.id.resource.resourceId), newPolicy.id.accessPolicyName)
    cs.evalOn(blockingEc)(IO(ldapConnectionPool.modify(policyDn(ridPolicyName), memberMod, actionMod, roleMod, dateMod, publicMod))) *> newPolicy.pure[IO]
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = {
    for{
      policyDnPattern <- IO(dnMatcher(Seq(Attr.policy, Attr.resourceId), resourceTypeDn(resourceTypeName)))
      entry <- cs.evalOn(blockingEc)(IO(ldapConnectionPool.getEntry(userDn(userId), Attr.memberOf)))
    } yield {
      val groupDns = Option(entry).flatMap(e => Option(getAttributes(e, Attr.memberOf))).getOrElse(Set.empty)
      groupDns.collect{case policyDnPattern(policyName, resourceId) => ResourceIdAndPolicyName(ResourceId(resourceId), AccessPolicyName(policyName))}
    }
  }

  override def listResourceWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): IO[Set[Resource]] = {
    val filters = resourceId.grouped(batchSize).map(
      batch =>
        Filter.createORFilter(
          batch.map(r => Filter.createANDFilter(Filter.createEqualityFilter(Attr.resourceId, r.value), Filter.createEqualityFilter(Attr.objectClass, ObjectClass.resource))
          ).asJava)).toSeq

    for{
      stream <- cs.evalOn(blockingEc)(IO(ldapSearchStream(resourceTypeDn(resourceTypeName), SearchScope.ONE, filters:_*)(et => unmarshalResourceAuthDomain(et, resourceTypeName))))
      res <- IO.fromEither(stream.parSequence.leftMap(s => new WorkbenchException(s)))
    } yield res.toSet
  }

  private def unmarshalResourceAuthDomain(entry: Entry, resourceTypeName: ResourceTypeName): Either[String, Resource] = {
    val authDomains = getAttributes(entry, Attr.authDomain).map(WorkbenchGroupName)
    for{
      resourceId <- getAttribute(entry, Attr.resourceId).toRight(s"${entry.getDN} attribute missing: ${Attr.resourceId}. ")
    } yield Resource(resourceTypeName, ResourceId(resourceId), authDomains)
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]] = IO(
    ldapSearchStream(resourceTypeDn(resourceTypeName), SearchScope.SUB, Filter.createANDFilter(Filter.createEqualityFilter("objectclass", ObjectClass.policy), Filter.createEqualityFilter(Attr.public, "true"))) { entry =>
      val policy = unmarshalAccessPolicy(entry)
      ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName)
    }
  )

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = {
    cs.evalOn(blockingEc)(
      IO(
        ldapSearchStream(
          resourceDn(resource),
          SearchScope.SUB,
          Filter.createANDFilter(
            Filter.createEqualityFilter("objectclass", ObjectClass.policy),
            Filter.createEqualityFilter(Attr.public, "true"))
        )(unmarshalAccessPolicy)))
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId): IO[Set[AccessPolicy]] = cs.evalOn(blockingEc)(IO(
    ldapSearchStream(resourceDn(resource), SearchScope.SUB, Filter.createEqualityFilter("objectclass", ObjectClass.policy))(unmarshalAccessPolicy).toSet
  ))

  override def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): IO[Set[AccessPolicy]] = for {
    entry <- cs.evalOn(blockingEc)(IO(ldapConnectionPool.getEntry(subjectDn(user), Attr.memberOf)))
    members <- IO.pure(Option(entry).map(e => getAttributes(e, Attr.memberOf).toList))
    accessPolicies <- members.traverse{
      policyStrs =>
        val fullyQualifiedPolicyIds = policyStrs.mapFilter{
          str =>
            for{
              subject <- Either.catchNonFatal(dnToSubject(str)).toOption
              fullyQualifiedPolicyId <- subject match {
                case sub: FullyQualifiedPolicyId if sub.resource.resourceId == resource.resourceId && sub.resource.resourceTypeName == resource.resourceTypeName => Some(sub)
                case _ => None
              }
            } yield fullyQualifiedPolicyId
        }
        val filters = fullyQualifiedPolicyIds.grouped(batchSize).map(
          batch => Filter.createORFilter(batch.map(r => Filter.createEqualityFilter(Attr.policy, r.accessPolicyName.value)).asJava)).toSeq
        cs.evalOn(blockingEc)(IO(ldapSearchStream(resourceDn(resource), SearchScope.SUB, filters: _*)(unmarshalAccessPolicy).toSet))
    }
  } yield accessPolicies.getOrElse(Set.empty)

  override def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): IO[Set[WorkbenchUserId]] = cs.evalOn(blockingEc)(
    IO(ldapSearchStream(peopleOu, SearchScope.ONE, Filter.createEqualityFilter(Attr.memberOf, policyDn(policyId)))(unmarshalUser).map(_.id).toSet)
  )

  private def unmarshalUser(entry: Entry): WorkbenchUser = {
    val uid = getAttribute(entry, Attr.uid).getOrElse(throw new WorkbenchException(s"${Attr.uid} attribute missing"))
    val email = getAttribute(entry, Attr.email).getOrElse(throw new WorkbenchException(s"${Attr.email} attribute missing"))

    WorkbenchUser(WorkbenchUserId(uid), getAttribute(entry, Attr.googleSubjectId).map(GoogleSubjectId), WorkbenchEmail(email))
  }

  override def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, public: Boolean): IO[Unit] = {
    val dateMod = new Modification(ModificationType.REPLACE, Attr.groupUpdatedTimestamp, formattedDate(new Date()))
    val publicMod = new Modification(ModificationType.REPLACE, Attr.public, public.toString)

    cs.evalOn(blockingEc)(IO(ldapConnectionPool.modify( policyDn(policyId), dateMod, publicMod)).void).recoverWith {
      case ldape: LDAPException if ldape.getResultCode == ResultCode.NO_SUCH_OBJECT =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy does not exist")))
    }
  }
}
