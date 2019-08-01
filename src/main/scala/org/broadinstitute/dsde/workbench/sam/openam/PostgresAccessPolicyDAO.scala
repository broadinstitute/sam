package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends AccessPolicyDAO with DatabaseSupport with LazyLogging {

  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)


  def createResourceType(resourceType: ResourceType): IO[ResourceType] = {
    // Check that actions match action patterns
    validateRoleActions(resourceType)
      runInTransaction { implicit session =>
        // Create resource type
        val resourceTypePK = insertResourceType(resourceType.name)
        // Create action patterns
        insertActionPatterns(resourceType.actionPatterns, resourceTypePK)
        // Create actions
        // Create roles
        // Create role actions
        insertRolesAndActions(resourceType.roles, resourceTypePK)

        resourceType
    }
  }

  private def validateRoleActions(resourceType: ResourceType) = {
    val invalidActions = resourceType.roles.flatMap(_.actions).filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
    if (invalidActions.nonEmpty) throw new WorkbenchException(s"ResourceType ${resourceType.name} had invalid actions ${invalidActions}")
  }

  private def insertRolesAndActions(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession) = {
    val resourceRoleTableColumn = ResourceRoleTable.column
    val resourceActionTableColumn = ResourceActionTable.column
    val roleActionTableColumn = RoleActionTable.column
    val r  = ResourceRoleTable.syntax("r")
    val a  = ResourceActionTable.syntax("a")
    val ra = RoleActionTable.syntax("ra")

    val uniqueActions = roles.map(_.actions).flatten
    val uniqueActionValues = uniqueActions map { action =>
      samsqls"(${resourceTypePK}, ${action})"
    }

     val insertActionQuery = samsql"""insert into ${ResourceActionTable.table}
                                      (${resourceActionTableColumn.resourceTypeId}, ${resourceActionTableColumn.action})
                                      values ${uniqueActionValues}"""

      ResourceActionPK(insertActionQuery.updateAndReturnGeneratedKey().apply())


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
      samsqls"(${resourceTypePK}, ${actionPattern.value}, ${actionPattern.description}, ${actionPattern.authDomainConstrainable})"
    }
    val actionPatternQuery =
      samsql"""insert into ${ResourceActionPatternTable.table}
              (${resourceActionPatternTableColumn.resourceTypeId},
               ${resourceActionPatternTableColumn.actionPattern},
               ${resourceActionPatternTableColumn.description},
               ${resourceActionPatternTableColumn.isAuthDomainConstrainable})
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

  def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[LoadResourceAuthDomainResult] = {
    runInTransaction { implicit session =>
      val ad = AuthDomainTable.syntax("ad")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")
      val g = GroupTable.syntax("g")

      // left joins below so we can detect the difference between a resource does not exist vs.
      // a resource exists but does not have any auth domains
      val query = samsql"""select ${g.result.name}
              from ${ResourceTable as r}
              join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              left outer join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
              left outer join ${GroupTable as g} on  ${ad.groupId} = ${g.id}
              where ${r.name} = ${resource.resourceId} and ${rt.name} = ${resource.resourceTypeName}"""

      val results = query.map(_.stringOpt(g.resultName.name)).list().apply()

      // there are 3 expected forms for the results:
      //   1) empty list - nothing matched inner joins
      //   2) 1 row with a None - nothing matched left joins
      //   3) non-empty list of non-None group names
      // there are 2 unexpected forms
      //   a) more than one row, all Nones
      //   b) more than one row, some Nones
      // a is treated like 2 and b is treated like 3 with Nones removed
      val authDomains = NonEmptyList.fromList(results.flatten) // flatten removes Nones
      authDomains match {
        case None =>
          if (results.isEmpty) {
            ResourceNotFound // case 1
          } else {
            NotConstrained  // case 2
          }
        case Some(nel) => Constrained(nel.map(WorkbenchGroupName)) // case 3
      }
    }
  }

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
