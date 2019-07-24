package org.broadinstitute.dsde.workbench.sam.openam

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchSubject, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables.{ResourceTypePK, ResourceTypeTable}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import scalikejdbc._

import scala.concurrent.ExecutionContext

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DatabaseSupport {

  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName] = {
    runInTransaction { implicit session =>
      insertResourceType(resourceTypeName)
      resourceTypeName
    }
  }

  def insertResourceType(resourceTypeName: ResourceTypeName)(implicit session: DBSession): ResourceTypePK = {
    val resourceTypeTableColumn = ResourceTypeTable.column
    val insertResourceTypeQuery = samsql"""insert into ${ResourceTypeTable.table} (${resourceTypeTableColumn.resourceTypeName}) values (${resourceTypeName.value})"""

    ResourceTypePK(insertResourceTypeQuery.updateAndReturnGeneratedKey().apply())
  }


  def createResource(resource: Resource): IO[Resource] = ???
  def deleteResource(resource: FullyQualifiedResourceId): IO[Unit] = ???
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[LoadResourceAuthDomainResult] = ???
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]] = ???
  def createPolicy(policy: AccessPolicy): IO[AccessPolicy] = ???
  def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit] = ???
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Option[AccessPolicy]] = ???
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit] = ???
  def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy] = ???
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]] = ???
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = ???
  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): IO[Set[Resource]] = ???
  def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId): IO[Option[Resource]] = ???
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = ???
  def listAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = ???
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): IO[Set[AccessPolicy]] = ???
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): IO[Set[WorkbenchUser]] = ???
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit] = ???
  def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit] = ???
}
