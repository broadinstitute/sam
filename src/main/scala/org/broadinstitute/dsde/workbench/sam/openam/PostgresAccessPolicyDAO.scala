package org.broadinstitute.dsde.workbench.sam.openam

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, SamTypeBinders}
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
    val uniqueActions = resourceType.roles.flatMap(_.actions)
    runInTransaction { implicit session =>
      val resourceTypePK = insertResourceType(resourceType.name)

      insertActionPatterns(resourceType.actionPatterns, resourceTypePK)
      insertActions(uniqueActions, resourceTypePK)
      insertRoles(resourceType.roles, resourceTypePK)
      insertRoleActions(resourceType.roles, resourceTypePK)

      resourceType
    }
  }

  // The Actions that get created for a ResourceType must regex.match at least one ActionPattern for that ResourceType
  // This method collects the actions that do not match at least 1 ActionPattern for this resourceType and lists them
  // out in an exception
  // Method is side-effecty.  All actions are valid if there was no exception thrown
  private def validateRoleActions(resourceType: ResourceType): Unit = {
    val invalidActions = resourceType.roles.flatMap(_.actions).filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
    if (invalidActions.nonEmpty) throw new WorkbenchException(s"ResourceType ${resourceType.name} had invalid actions ${invalidActions}")
  }

  private def insertRoleActions(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val resourceTypeActions = selectActionsForResourceType(resourceTypePK)
    val resourceTypeRoles = selectRolesForResourceType(resourceTypePK)

    val roleActionValues = roles.flatMap { role =>
      val maybeRolePK = resourceTypeRoles.find(r => r.role == role.roleName).map(_.id)
      val actionPKs = resourceTypeActions.filter(rta => role.actions.contains(rta.action)).map(_.id)

      val rolePK = maybeRolePK.getOrElse(throw new WorkbenchException(s"Cannot add Role Actions because Role '${role.roleName}' does not exist for ResourceType: ${resourceTypePK}"))

      actionPKs.map(actionPK => samsqls"(${rolePK}, ${actionPK})")
    }

    val insertQuery = samsql"insert into ${RoleActionTable.table}(${RoleActionTable.column.resourceRoleId}, ${RoleActionTable.column.resourceActionId}) values ${roleActionValues}"
    insertQuery.update().apply()
  }

  private def selectActionsForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceActionRecord] = {
    val actionsQuery =
      samsql"""select *
               from ${ResourceActionTable.table}
               where ${ResourceActionTable.column.resourceTypeId} = ${resourceTypePK}"""

    import SamTypeBinders._
    actionsQuery.map{ rs =>
      ResourceActionRecord(rs.get[ResourceActionPK](1), rs.get[ResourceTypePK](2), rs.get[ResourceAction](3))
    }.list().apply()
  }

  private def selectRolesForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceRoleRecord] = {
    val actionsQuery =
      samsql"""select *
               from ${ResourceRoleTable.table}
               where ${ResourceRoleTable.column.resourceTypeId} = ${resourceTypePK}"""

    import SamTypeBinders._
    actionsQuery.map{ rs =>
      ResourceRoleRecord(rs.get[ResourceRolePK](1), rs.get[ResourceTypePK](2), rs.get[ResourceRoleName](3))
    }.list().apply()
  }

  private def insertRoles(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val roleValues = roles.map(role => samsqls"(${resourceTypePK}, ${role.roleName})")
    val insertRolesQuery =
      samsql"""insert into ${ResourceRoleTable.table}(${ResourceRoleTable.column.resourceTypeId}, ${ResourceRoleTable.column.role})
                 values ${roleValues}"""

    insertRolesQuery.update().apply()
  }

  // Note: Each ResourceAction that you are saving here should match the regex pattern of at least 1
  // ResourceActionPattern defined for the specified ResourceType.  This method DOES NOT perform that validation for
  // you.  It is up to the caller to make sure the actions being saved are valid.
  private def insertActions(actions: Set[ResourceAction], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val uniqueActionValues = actions.map { action =>
      samsqls"(${resourceTypePK}, ${action})"
    }

    val insertActionQuery = samsql"""insert into ${ResourceActionTable.table}(${ResourceActionTable.column.resourceTypeId}, ${ResourceActionTable.column.action}) values ${uniqueActionValues}"""

    insertActionQuery.update().apply()
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
