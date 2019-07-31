package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DatabaseSupport with LazyLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  // Create resourceType should do the following:
  // 1. Create the resource type
  // 2. Add the roles
  // 3. Add the actions
  // 4. Add patterns
  def createResourceType(resourceType: ResourceType): IO[ResourceType] = {
    runInTransaction { implicit session =>
      val resourceTypePK = insertResourceType(resourceType.name)
      insertRolesAndActions(resourceType.roles, resourceTypePK)
      insertActionPatterns(resourceType.actionPatterns, resourceTypePK)
      resourceType
    }
  }

  private def insertRolesAndActions(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession) = {
    val resourceRoleTableColumn = ResourceRoleTable.column
    val resourceActionTableColumn = ResourceActionTable.column
    val roleActionTableColumn = RoleActionTable.column
    val r  = ResourceRoleTable.syntax("r")
    val a  = ResourceActionTable.syntax("a")
    val ra = RoleActionTable.syntax("ra")

    val uniqueActions = roles.map(_.actions).flatten
    val actionPKs = uniqueActions map { action =>
        val insertActionQuery =
          samsql"""insert into ${ResourceActionTable.table}
                   (${resourceActionTableColumn.resourceTypeId}, ${resourceActionTableColumn.action})
                   values (${resourceTypePK}, ${action})"""

        ResourceActionPK(insertActionQuery.updateAndReturnGeneratedKey().apply())
    }

    // 1. Add role to ResourceRole table
    // 2. Add every action for each role to the ResourceAction table
    // 3. Add each Resource Role and Resource Action to the RoleAction table <-- Is this what actually happens?
    roles map { role =>
      val insertRoleQuery =
        samsql"""insert into ${ResourceRoleTable.table}
                 (${resourceRoleTableColumn.resourceTypeId}, ${resourceRoleTableColumn.role})
                 values (${resourceTypePK}, ${role.roleName})"""
      val resourceRolePK = ResourceRolePK(insertRoleQuery.updateAndReturnGeneratedKey().apply())

        val insertRoleActionQuery =
          samsql"""insert into ${RoleActionTable.table}
                   select ${r.id}, ${a.id}
                   from ${ResourceRoleTable as r}, ${ResourceActionTable as a}
                   where ${r.resourceTypeId} = ${resourceTypePK}
                   and   ${a.resourceTypeId} = ${resourceTypePK}
                   and   ${r.role}   = ${role.roleName}
                   and   ${a.action} IN (${role.actions})
            """
        insertRoleActionQuery.update().apply()
      }
  }

  private def insertActionPatterns(actionPatterns: Set[ResourceActionPattern], resourceTypePK: ResourceTypePK)(implicit session: DBSession) = {
    val resourceActionPatternTableColumn = ResourceActionPatternTable.column
    val actionPatternValues = actionPatterns map { actionPattern =>
      samsqls"(${resourceTypePK}, ${actionPattern.value})"
    }
    val actionPatternQuery =
      samsql"""insert into ${ResourceActionPatternTable.table}
              (${resourceActionPatternTableColumn.resourceTypeId}, ${resourceActionPatternTableColumn.actionPattern})
              values ${actionPatternValues}"""
    actionPatternQuery.update().apply()
  }

  private def insertResourceType(resourceTypeName: ResourceTypeName)(implicit session: DBSession): ResourceTypePK = {
    val resourceTypeTableColumn = ResourceTypeTable.column
    val insertResourceTypeQuery = samsql"""insert into ${ResourceTypeTable.table} (${resourceTypeTableColumn.name}) values (${resourceTypeName.value})"""

    ResourceTypePK(insertResourceTypeQuery.updateAndReturnGeneratedKey().apply())
  }

  // 1. Create Resource
  // 2. Create the entries in the join table for the auth domains
  def createResource(resource: Resource): IO[Resource] = {
    runInTransaction { implicit session =>
      val resourcePK = insertResource(resource)

      if (resource.authDomain.nonEmpty) {
        insertAuthDomainsForResource(resourcePK, resource.authDomain)
      }

      resource
    }.recoverWith {
      case sqlException: PSQLException => {
        logger.debug(s"createResource psql exception on resource $resource", sqlException)
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"Resource ${resource.resourceTypeName} failed.", sqlException)))
      }
    }
  }

  private def insertResource(resource: Resource)(implicit session: DBSession): ResourcePK = {
    val resourceTableColumn = ResourceTable.column
    val insertResourceQuery =
      samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId})
               values (${resource.resourceId}, (${loadResourceTypePK(resource.resourceTypeName)}))"""
    ResourcePK(insertResourceQuery.updateAndReturnGeneratedKey().apply())
  }

  private def insertAuthDomainsForResource(resourcePK: ResourcePK, authDomains: Set[WorkbenchGroupName])(implicit session: DBSession): Int = {
    val authDomainValues = authDomains.map { authDomain =>
      samsqls"(${resourcePK}, (${GroupTable.groupPKQueryForGroup(authDomain)}))"
    }

    val authDomainColumn = AuthDomainTable.column
    val insertAuthDomainQuery = samsql"insert into ${AuthDomainTable.table} (${authDomainColumn.resourceId}, ${authDomainColumn.groupId}) values ${authDomainValues}"
    insertAuthDomainQuery.update().apply()
  }

  private def loadResourceTypePK(resourceTypeName: ResourceTypeName, resourceTypeTableAlias: String = "rt"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    samsqls"""select ${rt.id} from ${ResourceTypeTable as rt} where ${rt.name} = ${resourceTypeName}"""
  }

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
