package org.broadinstitute.dsde.workbench.sam.openam

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, PSQLStateExtensions, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.dao.{PostgresGroupDAO, SubGroupMemberTable}
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit val cs: ContextShift[IO]) extends AccessPolicyDAO with DatabaseSupport with PostgresGroupDAO with LazyLogging {

  // This method obtains an EXCLUSIVE lock on the ResourceType table because if we boot multiple Sam instances at once,
  // they will all try to (re)create ResourceTypes at the same time and could collide. The lock is automatically
  // released at the end of the transaction.
  override def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType] = {
    val uniqueActions = resourceType.roles.flatMap(_.actions)
    runInTransaction("createResourceType", samRequestContext)({ implicit session =>
      samsql"lock table ${ResourceTypeTable.table} IN EXCLUSIVE MODE".execute().apply()
      val resourceTypePK = insertResourceType(resourceType.name)

      overwriteActionPatterns(resourceType.actionPatterns, resourceTypePK)
      overwriteRoles(resourceType.roles, resourceTypePK)
      insertActions(uniqueActions, resourceTypePK)
      overwriteRoleActions(resourceType.roles, resourceTypePK)

      resourceType
    })
  }

  private def overwriteRoleActions(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    // Load Actions and Roles from DB because we need their DB IDs
    val resourceTypeActions = selectActionsForResourceType(resourceTypePK)
    val resourceTypeRoles = selectRolesForResourceType(resourceTypePK)

    val roleActionValues = roles.flatMap { role =>
      val maybeRolePK = resourceTypeRoles.find(r => r.role == role.roleName).map(_.id)
      val actionPKs = resourceTypeActions.filter(rta => role.actions.contains(rta.action)).map(_.id)

      val rolePK = maybeRolePK.getOrElse(throw new WorkbenchException(s"Cannot add Role Actions because Role '${role.roleName}' does not exist for ResourceType: ${resourceTypePK}"))

      actionPKs.map(actionPK => samsqls"(${rolePK}, ${actionPK})")
    }

    val ra = RoleActionTable.syntax("ra")
    val rr = ResourceRoleTable.syntax("rr")
    if (roleActionValues.isEmpty) {
      samsql"""delete from ${RoleActionTable as ra}
                 using ${ResourceRoleTable as rr}
                 where ${ra.resourceRoleId} = ${rr.id}
                 and ${rr.resourceTypeId} = ${resourceTypePK}"""
        .update().apply()
    } else {
      samsql"""delete from ${RoleActionTable as ra}
                 where (${ra.resourceRoleId}, ${ra.resourceActionId}) not in (${roleActionValues})"""
        .update().apply()

      val insertQuery =
        samsql"""insert into ${RoleActionTable.table}(${RoleActionTable.column.resourceRoleId}, ${RoleActionTable.column.resourceActionId})
                    values ${roleActionValues}
                    on conflict do nothing"""
      insertQuery.update().apply()
    }
  }

  private def selectActionsForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceActionRecord] = {
    val rat = ResourceActionTable.syntax("rat")
    val actionsQuery =
      samsql"""select ${rat.result.*}
               from ${ResourceActionTable as rat}
               where ${rat.resourceTypeId} = ${resourceTypePK}"""

    actionsQuery.map(ResourceActionTable(rat.resultName)).list().apply()
  }

  private def selectRolesForResourceType(resourceTypePK: ResourceTypePK)(implicit session: DBSession): List[ResourceRoleRecord] = {
    val rrt = ResourceRoleTable.syntax("rrt")
    val actionsQuery =
      samsql"""select ${rrt.result.*}
               from ${ResourceRoleTable as rrt}
               where ${rrt.resourceTypeId} = ${resourceTypePK}
               and ${rrt.deprecated} = false"""

    actionsQuery.map(ResourceRoleTable(rrt.resultName)).list().apply()
  }

  private def overwriteRoles(roles: Set[ResourceRole], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val roleValues = roles.map(role => samsqls"(${resourceTypePK}, ${role.roleName})")

    val resourceRoleColumn = ResourceRoleTable.column
    if (roles.isEmpty) {
      samsql"""update ${ResourceRoleTable.table}
            set ${resourceRoleColumn.deprecated} = true
            where ${resourceRoleColumn.resourceTypeId} = ${resourceTypePK}"""
        .update().apply()
    } else {
      samsql"""update ${ResourceRoleTable.table}
              set ${resourceRoleColumn.deprecated} = true
              where ${resourceRoleColumn.resourceTypeId} = ${resourceTypePK}
              and ${resourceRoleColumn.role} not in (${roles.map(_.roleName)})"""
        .update().apply()

      val insertRolesQuery =
        samsql"""insert into ${ResourceRoleTable.table}(${resourceRoleColumn.resourceTypeId}, ${resourceRoleColumn.role})
               values ${roleValues}
               on conflict do nothing"""

      insertRolesQuery.update().apply()
    }
  }

  private def insertActions(actions: Set[ResourceAction], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    if (actions.isEmpty) {
      return 0
    } else {
      val uniqueActionValues = actions.map { action =>
        samsqls"(${resourceTypePK}, ${action})"
      }

      val insertActionQuery =
        samsql"""insert into ${ResourceActionTable.table}(${ResourceActionTable.column.resourceTypeId}, ${ResourceActionTable.column.action})
                    values ${uniqueActionValues}
                 on conflict do nothing"""

      insertActionQuery.update().apply()
    }
  }

  /**
    * Performs an UPSERT when a record already exists for this exact ActionPattern for this ResourceType.  Query will
    * rerwrite the `description` and `isAuthDomainConstrainable` columns if the ResourceTypeActionPattern already
    * exists.
    */
  private def overwriteActionPatterns(actionPatterns: Set[ResourceActionPattern], resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val rap = ResourceActionPatternTable.syntax("rap")

    if (actionPatterns.isEmpty) {
      samsql"""delete from ${ResourceActionPatternTable as rap}
            where ${rap.resourceTypeId} = ${resourceTypePK}"""
        .update().apply()
    } else {
      samsql"""delete from ${ResourceActionPatternTable as rap}
            where ${rap.resourceTypeId} = ${resourceTypePK}
            and ${rap.actionPattern} not in (${actionPatterns.map(_.value)})"""
        .update().apply()

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
                  values ${actionPatternValues}
               on conflict (${resourceActionPatternTableColumn.resourceTypeId}, ${resourceActionPatternTableColumn.actionPattern})
                  do update
                      set ${resourceActionPatternTableColumn.description} = EXCLUDED.${resourceActionPatternTableColumn.description},
          ${resourceActionPatternTableColumn.isAuthDomainConstrainable} = EXCLUDED.${resourceActionPatternTableColumn.isAuthDomainConstrainable}"""
      actionPatternQuery.update().apply()
    }
  }

  // This method needs to always return the ResourceTypePK, regardless of whether we just inserted the ResourceType or
  // it already existed.  We do this by using a `RETURNING` statement.  This statement will only return values for rows
  // that were created or updated.  Therefore, `ON CONFLICT` will update the row with the same name value that was there
  // before and the previously existing ResourceType id will be returned.
  private def insertResourceType(resourceTypeName: ResourceTypeName)(implicit session: DBSession): ResourceTypePK = {
    val resourceTypeTableColumn = ResourceTypeTable.column
    val insertResourceTypeQuery =
      samsql"""insert into ${ResourceTypeTable.table} (${resourceTypeTableColumn.name})
                  values (${resourceTypeName.value})
               on conflict (${ResourceTypeTable.column.name})
                  do update set ${ResourceTypeTable.column.name}=EXCLUDED.${ResourceTypeTable.column.name}
               returning ${ResourceTypeTable.column.id}"""

    ResourceTypePK(insertResourceTypeQuery.updateAndReturnGeneratedKey().apply())
  }

  // 1. Create Resource
  // 2. Create the entries in the join table for the auth domains
  override def createResource(resource: Resource, samRequestContext: SamRequestContext): IO[Resource] = {
    runInTransaction("createResource", samRequestContext)({ implicit session =>
      val resourcePK = insertResource(resource)

      if (resource.authDomain.nonEmpty) {
        insertAuthDomainsForResource(resourcePK, resource.authDomain)
      }

      resource
    })
  }

  private def insertResource(resource: Resource)(implicit session: DBSession): ResourcePK = {
    val resourceTableColumn = ResourceTable.column
    val insertResourceQuery =
      samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId})
               values (${resource.resourceId}, (${loadResourceTypePK(resource.resourceTypeName)}))"""

    Try {
      ResourcePK(insertResourceQuery.updateAndReturnGeneratedKey().apply())
    }.recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists")))
    }.get
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

  override def deleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    runInTransaction("deleteResource", samRequestContext)({ implicit session =>
      val r = ResourceTable.syntax("r")
      samsql"delete from ${ResourceTable as r} where ${r.name} = ${resource.resourceId} and ${r.resourceTypeId} = (${loadResourceTypePK(resource.resourceTypeName)})".update().apply()
    })
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LoadResourceAuthDomainResult] = {
    runInTransaction("loadResourceAuthDomain", samRequestContext)({ implicit session =>
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
    })
  }

  override def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[Resource]] = {
    import SamTypeBinders._

    runInTransaction("listResourcesConstrainedByGroup", samRequestContext)({ implicit session =>
      val r = ResourceTable.syntax("r")
      val ad = AuthDomainTable.syntax("ad")
      val g = GroupTable.syntax("g")
      val rt = ResourceTypeTable.syntax("rt")
      val p = PolicyTable.syntax("p")

      val constrainedResourcesPKs = groupId match {
        case group: WorkbenchGroupName =>
          samsqls"""select ${ad.result.resourceId}
           from ${AuthDomainTable as ad}
           join ${GroupTable as g} on ${g.id} = ${ad.groupId}
           where ${g.name} = ${group}"""
        case policy: FullyQualifiedPolicyId =>
          samsqls"""select ${ad.result.resourceId}
           from ${AuthDomainTable as ad}
           join ${PolicyTable as p} on ${ad.groupId} = ${p.groupId}
           join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
           join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
           where ${policy.accessPolicyName} = ${p.name}
           and ${policy.resource.resourceId} = ${r.name}
           and ${policy.resource.resourceTypeName} = ${rt.name}"""
      }

      val results = samsql"""select ${rt.result.name}, ${r.result.name}, ${g.result.name}
                      from ${ResourceTable as r}
                      join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                      left join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
                      left join ${GroupTable as g} on ${ad.groupId} = ${g.id}
                      where ${r.id} in (${constrainedResourcesPKs})"""
        .map(rs => (rs.get[ResourceTypeName](rt.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[WorkbenchGroupName](g.resultName.name))).list().apply()

      val resultsByResource = results.groupBy(result => (result._1, result._2))
      resultsByResource.map {
        case ((resourceTypeName, resourceId), groupedResults) => Resource(resourceTypeName, resourceId, groupedResults.collect {
          case (_, _, authDomainGroupName) => authDomainGroupName
        }.toSet)
      }.toSet
    })
  }

  override def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    val r = ResourceTable.syntax("r")
    val ad = AuthDomainTable.syntax("ad")
    val rt = ResourceTypeTable.syntax("rt")

    runInTransaction("removeAuthDomainFromResource", samRequestContext)({ implicit session =>
      samsql"""delete from ${AuthDomainTable as ad}
              where ${ad.resourceId} =
              (select ${r.id} from ${ResourceTable as r}
              join ${ResourceTypeTable as rt}
              on ${r.resourceTypeId} = ${rt.id}
              where ${r.name} = ${resource.resourceId}
              and ${rt.name} = ${resource.resourceTypeName})""".update().apply()
    })
  }

  override def createPolicy(policy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = {
    runInTransaction("createPolicy", samRequestContext)({ implicit session =>
      val groupId = insertPolicyGroup(policy)
      val policyId = insertPolicy(policy, groupId)

      insertGroupMembers(groupId, policy.members)

      insertPolicyRoles(policy.roles, policyId)
      insertPolicyActions(policy.actions, policyId)

      policy
    }).recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"policy $policy already exists")))
    }
  }

  private def insertPolicyActions(actions: Set[ResourceAction], policyId: PolicyPK)(implicit session: DBSession): Int = {
    val ra = ResourceActionTable.syntax("ra")
    val rt = ResourceTypeTable.syntax("rt")
    val r = ResourceTable.syntax("r")
    val p = PolicyTable.syntax("p")
    val paCol = PolicyActionTable.column
    if (actions.nonEmpty) {
      val insertQuery = samsqls"""insert into ${PolicyActionTable.table} (${paCol.resourcePolicyId}, ${paCol.resourceActionId})
            select ${policyId}, ${ra.result.id}
            from ${ResourceActionTable as ra}
            join ${ResourceTypeTable as rt} on ${ra.resourceTypeId} = ${rt.id}
            join ${ResourceTable as r} on ${r.resourceTypeId} = ${rt.id}
            join ${PolicyTable as p} on ${p.resourceId} = ${r.id}
            where ${ra.action} in (${actions})
            and ${p.id} = ${policyId}"""

      val inserted = samsql"$insertQuery".update().apply()

      if (inserted != actions.size) {
        // in this case some actions that we want to insert did not exist in ResourceActionTable
        // add them now and rerun the insert ignoring conflicts
        // this case should happen rarely
        import SamTypeBinders._
        val resourceTypePK = samsql"select ${r.result.resourceTypeId} from ${PolicyTable as p} join ${ResourceTable as r} on ${p.resourceId} = ${r.id} where ${p.id} = ${policyId}"
          .map(rs => rs.get[ResourceTypePK](r.resultName.resourceTypeId)).single().apply().getOrElse(throw new WorkbenchException(s"could not find resource type id for policy id $policyId"))
        insertActions(actions, resourceTypePK)

        val moreInserted = samsql"$insertQuery on conflict do nothing".update().apply()

        inserted + moreInserted
      } else {
        inserted
      }
    } else {
      0
    }
  }

  private def insertPolicyRoles(roles: Set[ResourceRoleName], policyId: PolicyPK)(implicit session: DBSession): Int = {
    val rr = ResourceRoleTable.syntax("rr")
    val rt = ResourceTypeTable.syntax("rt")
    val r = ResourceTable.syntax("r")
    val p = PolicyTable.syntax("p")
    val prCol = PolicyRoleTable.column
    if (roles.nonEmpty) {
      val insertedRolesCount = samsql"""insert into ${PolicyRoleTable.table} (${prCol.resourcePolicyId}, ${prCol.resourceRoleId})
            select ${policyId}, ${rr.result.id}
            from ${ResourceRoleTable as rr}
            join ${ResourceTypeTable as rt} on ${rr.resourceTypeId} = ${rt.id}
            join ${ResourceTable as r} on ${r.resourceTypeId} = ${rt.id}
            join ${PolicyTable as p} on ${p.resourceId} = ${r.id}
            where ${rr.role} in (${roles})
            and ${rr.deprecated} = false
            and ${p.id} = ${policyId}"""
        .update().apply()
      if (insertedRolesCount != roles.size) {
        throw new WorkbenchException("Some roles have been deprecated or were not found.")
      }
      insertedRolesCount
    } else {
      0
    }
  }

  private def insertPolicy(policy: AccessPolicy, groupId: GroupPK)(implicit session: DBSession): PolicyPK = {
    val pCol = PolicyTable.column
    PolicyPK(samsql"""insert into ${PolicyTable.table} (${pCol.resourceId}, ${pCol.groupId}, ${pCol.public}, ${pCol.name})
              values ((${ResourceTable.loadResourcePK(policy.id.resource)}), ${groupId}, ${policy.public}, ${policy.id.accessPolicyName})""".updateAndReturnGeneratedKey().apply())
  }

  private def insertPolicyGroup(policy: AccessPolicy)(implicit session: DBSession): GroupPK = {
    val gCol = GroupTable.column

    val policyGroupName = s"${policy.id.resource.resourceTypeName}_${policy.id.resource.resourceId}_${policy.id.accessPolicyName}"

    GroupPK(samsql"""insert into ${GroupTable.table} (${gCol.name}, ${gCol.email}, ${gCol.updatedDate})
               values (${policyGroupName}, ${policy.email}, ${Instant.now()})""".updateAndReturnGeneratedKey().apply())
  }

  // Policies and their roles and actions are set to cascade delete when the associated group is deleted
  override def deletePolicy(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit] = {
    val p = PolicyTable.syntax("p")
    val g = GroupTable.syntax("g")

    runInTransaction("deletePolicy", samRequestContext)({ implicit session =>
      val policyGroupPKOpt = samsql"""delete from ${PolicyTable as p}
        where ${p.name} = ${policy.accessPolicyName}
        and ${p.resourceId} = (${ResourceTable.loadResourcePK(policy.resource)})
        returning ${p.groupId}""".map(rs => rs.long(1)).single().apply()

      policyGroupPKOpt.map { policyGroupPK =>
        samsql"""delete from ${GroupTable as g}
           where ${g.id} = ${policyGroupPK}""".update().apply()
      }
    })
  }

  override def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]] = {
    listPolicies(resourceAndPolicyName.resource, limitOnePolicy = Option(resourceAndPolicyName.accessPolicyName), samRequestContext).map(_.headOption)
  }

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[Unit] = {
    runInTransaction("overwritePolicyMembers", samRequestContext)({ implicit session =>
      overwritePolicyMembersInternal(id, memberList)
    })
  }

  //Steps: Delete every member from the underlying group and then add all of the new members. Do this in a *single*
  //transaction so if any bit fails, all of it fails and we don't end up in some incorrect intermediate state.
  private def overwritePolicyMembersInternal(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    val gm = GroupMemberTable.syntax("gm")

    val groupId = samsql"${workbenchGroupIdentityToGroupPK(id)}".map(rs => rs.long(1)).single().apply().getOrElse {
      throw new WorkbenchException(s"Group for policy [$id] not found")
    }

    samsql"delete from ${GroupMemberTable as gm} where ${gm.groupId} = ${groupId}".update().apply()

    insertGroupMembers(GroupPK(groupId.toLong), memberList)
  }

  override def overwritePolicy(newPolicy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = {
    runInTransaction("overwritePolicy", samRequestContext)({ implicit session =>
      overwritePolicyMembersInternal(newPolicy.id, newPolicy.members)
      overwritePolicyRolesInternal(newPolicy.id, newPolicy.roles)
      overwritePolicyActionsInternal(newPolicy.id, newPolicy.actions)
      setPolicyIsPublicInternal(newPolicy.id, newPolicy.public)

      newPolicy
    })
  }

  private def overwritePolicyRolesInternal(id: FullyQualifiedPolicyId, roles: Set[ResourceRoleName])(implicit session: DBSession): Int = {
    val policyPK = getPolicyPK(id)

    val pr = PolicyRoleTable.syntax("pr")
    samsql"delete from ${PolicyRoleTable as pr} where ${pr.resourcePolicyId} = $policyPK".update().apply()

    insertPolicyRoles(roles, policyPK)
  }

  private def overwritePolicyActionsInternal(id: FullyQualifiedPolicyId, actions: Set[ResourceAction])(implicit session: DBSession): Int = {
    val policyPK = getPolicyPK(id)

    val pa = PolicyActionTable.syntax("pa")
    samsql"delete from ${PolicyActionTable as pa} where ${pa.resourcePolicyId} = $policyPK".update().apply()

    insertPolicyActions(actions, policyPK)
  }

  private def getPolicyPK(id: FullyQualifiedPolicyId)(implicit session: DBSession): PolicyPK = {
    val p = PolicyTable.syntax("p")
    samsql"select ${p.id} from ${PolicyTable as p} where ${p.name} = ${id.accessPolicyName} and ${p.resourceId} = (${ResourceTable.loadResourcePK(id.resource)})".map(rs => PolicyPK(rs.long(1))).single().apply().getOrElse {
      throw new WorkbenchException(s"policy record not found for $id")
    }
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[Stream[ResourceIdAndPolicyName]] = {
    val rt = ResourceTypeTable.syntax("rt")
    val r = ResourceTable.syntax("r")
    val p = PolicyTable.syntax("p")

    val query =
      samsql"""select ${r.result.name}, ${p.result.name}
               from ${ResourceTypeTable as rt}
               join ${ResourceTable as r} on ${r.resourceTypeId} = ${rt.id}
               join ${PolicyTable as p} on ${p.resourceId} = ${r.id}
               where ${rt.name} = ${resourceTypeName}
               and ${p.public} = true"""

    import SamTypeBinders._
    runInTransaction("listPublicAccessPolicies-(returns ResourceIdAndPolicyName)", samRequestContext)({ implicit session =>
      query.map(rs => ResourceIdAndPolicyName(rs.get[ResourceId](r.resultName.name), rs.get[AccessPolicyName](p.resultName.name))).list().apply().toStream
    })
  }

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Stream[AccessPolicyWithoutMembers]] = {
    val g = GroupTable.syntax("g")
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val p = PolicyTable.syntax("p")
    val pr = PolicyRoleTable.syntax("pr")
    val rr = ResourceRoleTable.syntax("rr")
    val pa = PolicyActionTable.syntax("pa")
    val ra = ResourceActionTable.syntax("ra")

    val listPoliciesQuery =
      samsql"""select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${rr.result.role}, ${ra.result.action}
          from ${GroupTable as g}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
          join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
          left join ${PolicyRoleTable as pr} on ${p.id} = ${pr.resourcePolicyId}
          left join ${ResourceRoleTable as rr} on ${pr.resourceRoleId} = ${rr.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          where ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}
          and ${p.public} = true"""

    import SamTypeBinders._
    runInTransaction("listPublicAccessPolicies-(returns AccessPolicyWithoutMembers)", samRequestContext)({ implicit session =>
      val results = listPoliciesQuery.map(rs => (PolicyInfo(rs.get[AccessPolicyName](p.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[ResourceTypeName](rt.resultName.name), rs.get[WorkbenchEmail](g.resultName.email), rs.boolean(p.resultName.public)),
        (rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)), rs.stringOpt(ra.resultName.action).map(ResourceAction(_))))).list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, roleActionResults) = resultsByPolicy.unzip

        val (roles, actions) = roleActionResults.unzip

        AccessPolicyWithoutMembers(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name), policyInfo.email, roles.flatten.toSet, actions.flatten.toSet, policyInfo.public)
      }.toStream
    })
  }

  // Abstracts logic to load and unmarshal one or more policies, use to get full AccessPolicy objects from Postgres
  private def listPolicies(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName] = None, samRequestContext: SamRequestContext): IO[Stream[AccessPolicy]] = {
    val g = GroupTable.syntax("g")
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val gm = GroupMemberTable.syntax("gm")
    val sg = GroupTable.syntax("sg")
    val p = PolicyTable.syntax("p")
    val pr = PolicyRoleTable.syntax("pr")
    val rr = ResourceRoleTable.syntax("rr")
    val pa = PolicyActionTable.syntax("pa")
    val ra = ResourceActionTable.syntax("ra")
    val sp = PolicyTable.syntax("sp")
    val sr = ResourceTable.syntax("sr")
    val srt = ResourceTypeTable.syntax("srt")

    val limitOnePolicyClause: SQLSyntax = limitOnePolicy match {
      case Some(policyName) => samsqls"and ${p.name} = ${policyName}"
      case None => samsqls""
    }

    val listPoliciesQuery =
      samsql"""select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${gm.result.memberUserId}, ${sg.result.name}, ${sp.result.name}, ${sr.result.name}, ${srt.result.name}, ${rr.result.role}, ${ra.result.action}
          from ${GroupTable as g}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
          join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
          left join ${GroupMemberTable as gm} on ${g.id} = ${gm.groupId}
          left join ${GroupTable as sg} on ${gm.memberGroupId} = ${sg.id}
          left join ${PolicyTable as sp} on ${sg.id} = ${sp.groupId}
          left join ${ResourceTable as sr} on ${sp.resourceId} = ${sr.id}
          left join ${ResourceTypeTable as srt} on ${sr.resourceTypeId} = ${srt.id}
          left join ${PolicyRoleTable as pr} on ${p.id} = ${pr.resourcePolicyId}
          left join ${ResourceRoleTable as rr} on ${pr.resourceRoleId} = ${rr.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          where ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}
          ${limitOnePolicyClause}"""

    import SamTypeBinders._
    runInTransaction("listPolicies", samRequestContext)({ implicit session =>
      val results = listPoliciesQuery.map(rs => (PolicyInfo(rs.get[AccessPolicyName](p.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[ResourceTypeName](rt.resultName.name), rs.get[WorkbenchEmail](g.resultName.email), rs.boolean(p.resultName.public)),
        (rs.stringOpt(gm.resultName.memberUserId).map(WorkbenchUserId), rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName), rs.stringOpt(sp.resultName.name).map(AccessPolicyName(_)), rs.stringOpt(sr.resultName.name).map(ResourceId(_)), rs.stringOpt(srt.resultName.name).map(ResourceTypeName(_))),
        (rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)), rs.stringOpt(ra.resultName.action).map(ResourceAction(_))))).list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, memberResults, roleActionResults) = resultsByPolicy.unzip3

        val members: Set[WorkbenchSubject] = memberResults.collect {
          case (Some(userId), None, None, None, None) => userId
          case (None, Some(groupName), None, None, None) => groupName
          case (None, Some(_), Some(policyName), Some(resourceName), Some(resourceTypeName)) => FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceName), policyName)
        }.toSet

        val (roles, actions) = roleActionResults.unzip

        AccessPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name), members, policyInfo.email, roles.flatten.toSet, actions.flatten.toSet, policyInfo.public)
      }.toStream
    })
  }

  override def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], samRequestContext: SamRequestContext): IO[Set[Resource]] = {
    import SamTypeBinders._

    if(resourceId.nonEmpty) {
      runInTransaction("listResourcesWithAuthdomains", samRequestContext)({ implicit session =>
        val r = ResourceTable.syntax("r")
        val ad = AuthDomainTable.syntax("ad")
        val g = GroupTable.syntax("g")
        val rt = ResourceTypeTable.syntax("rt")

        val results =
          samsql"""select ${r.result.name}, ${g.result.name}
                        from ${ResourceTable as r}
                        join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                        left join ${AuthDomainTable as ad} on ${r.id} = ${ad.resourceId}
                        left join ${GroupTable as g} on ${ad.groupId} = ${g.id}
                        where ${r.name} in (${resourceId}) and ${rt.name} = ${resourceTypeName}"""
            .map(rs => (rs.get[ResourceId](r.resultName.name), rs.stringOpt(g.resultName.name).map(WorkbenchGroupName))).list().apply()

        val resultsByResource = results.groupBy(_._1)
        resultsByResource.map {
          case (resource, groupedResults) => Resource(resourceTypeName, resource, groupedResults.collect {
            case (_, Some(authDomainGroupName)) => authDomainGroupName
          }.toSet)
        }.toSet
      })
    } else IO.pure(Set.empty)
  }

  override def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[Resource]] = {
    listResourcesWithAuthdomains(resourceId.resourceTypeName, Set(resourceId.resourceId), samRequestContext).map(_.headOption)
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceIdAndPolicyName]] = {
    val ancestorGroupsTable = SubGroupMemberTable("ancestor_groups")
    val ag = ancestorGroupsTable.syntax("ag")
    val agColumn = ancestorGroupsTable.column

    val pg = GroupMemberTable.syntax("parent_groups")
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val gm = GroupMemberTable.syntax("gm")
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")

    runInTransaction("listAccessPolicies", samRequestContext)({ implicit session =>
      import SamTypeBinders._

      samsql"""with recursive ${ancestorGroupsTable.table}(${agColumn.parentGroupId}) as (
                  select ${gm.groupId}
                  from ${GroupMemberTable as gm}
                  where ${gm.memberUserId} = ${userId}
                  union
                  select ${pg.groupId}
                  from ${GroupMemberTable as pg}
                  join ${ancestorGroupsTable as ag} on ${agColumn.parentGroupId} = ${pg.memberGroupId}
        ) select ${r.result.name}, ${p.result.name}
         from ${PolicyTable as p}
         join ${ancestorGroupsTable as ag} on ${ag.parentGroupId} = ${p.groupId}
         join ${ResourceTable as r} on ${r.id} = ${p.resourceId}
         join ${ResourceTypeTable as rt} on ${rt.id} = ${r.resourceTypeId}
         where ${rt.name} = ${resourceTypeName}""".map(rs => ResourceIdAndPolicyName(rs.get[ResourceId](r.resultName.name), rs.get[AccessPolicyName](p.resultName.name))).list().apply().toSet
    })
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Stream[AccessPolicy]] = {
    listPolicies(resource, samRequestContext = samRequestContext)
  }

  override def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[AccessPolicyWithoutMembers]] = {
    runInTransaction("listAccessPoliciesForUser", samRequestContext)({ implicit session =>
      val ancestorGroupsTable = SubGroupMemberTable("ancestor_groups")
      val ag = ancestorGroupsTable.syntax("ag")
      val agColumn = ancestorGroupsTable.column

      val pg = GroupMemberTable.syntax("parent_groups")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")
      val gm = GroupMemberTable.syntax("gm")
      val g = GroupTable.syntax("g")
      val p: scalikejdbc.QuerySQLSyntaxProvider[scalikejdbc.SQLSyntaxSupport[PolicyRecord], PolicyRecord] = PolicyTable.syntax("p")

      val pr = PolicyRoleTable.syntax("pr")
      val rr = ResourceRoleTable.syntax("rr")
      val pa = PolicyActionTable.syntax("pa")
      val ra = ResourceActionTable.syntax("ra")

      val listPoliciesQuery =
        samsql"""with recursive ${ancestorGroupsTable.table}(${agColumn.parentGroupId}) as (
                  select ${gm.groupId}
                  from ${GroupMemberTable as gm}
                  where ${gm.memberUserId} = ${user}
                  union
                  select ${pg.groupId}
                  from ${GroupMemberTable as pg}
                  join ${ancestorGroupsTable as ag} on ${agColumn.parentGroupId} = ${pg.memberGroupId}
        ) select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${rr.result.role}, ${ra.result.action}
          from ${GroupTable as g}
          join ${ancestorGroupsTable as ag} on ${ag.parentGroupId} = ${g.id}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
          join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
          left join ${PolicyRoleTable as pr} on ${p.id} = ${pr.resourcePolicyId}
          left join ${ResourceRoleTable as rr} on ${pr.resourceRoleId} = ${rr.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          where ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}"""

      import SamTypeBinders._
      val results = listPoliciesQuery.map(rs => (PolicyInfo(rs.get[AccessPolicyName](p.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[ResourceTypeName](rt.resultName.name), rs.get[WorkbenchEmail](g.resultName.email), rs.boolean(p.resultName.public)),
        (rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)), rs.stringOpt(ra.resultName.action).map(ResourceAction(_))))).list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, roleActionResults) = resultsByPolicy.unzip

        val (roles, actions) = roleActionResults.unzip

        AccessPolicyWithoutMembers(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name), policyInfo.email, roles.flatten.toSet, actions.flatten.toSet, policyInfo.public)
      }.toSet
    })
  }

  override def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Set[WorkbenchUser]] = {
    val subGroupMemberTable = SubGroupMemberTable("sub_group")
    val sg = subGroupMemberTable.syntax("sg")
    val u = UserTable.syntax("u")

    runInTransaction("listFlattenedPolicyMembers", samRequestContext)({ implicit session =>
      val query = samsql"""with recursive ${recursiveMembersQuery(policyId, subGroupMemberTable)}
        select ${u.resultAll}
        from ${subGroupMemberTable as sg}
        join ${UserTable as u} on ${u.id} = ${sg.memberUserId}"""

      query.map(UserTable(u)).list().apply().toSet.map(UserTable.unmarshalUserRecord)
    })
  }

  override def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean, samRequestContext: SamRequestContext): IO[Unit] = {
    runInTransaction("setPolicyIsPublic", samRequestContext)({ implicit session =>
      setPolicyIsPublicInternal(policyId, isPublic)
    })
  }

  private def setPolicyIsPublicInternal(policyId: FullyQualifiedPolicyId, isPublic: Boolean)(implicit session: DBSession): Int = {
    val p = PolicyTable.syntax("p")
    val policyTableColumn = PolicyTable.column
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")

    samsql"""update ${PolicyTable as p}
              set ${policyTableColumn.public} = ${isPublic}
              from ${ResourceTable as r}
              join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              where ${p.resourceId} = ${r.id}
              and ${p.name} = ${policyId.accessPolicyName}
              and ${r.name} = ${policyId.resource.resourceId}
              and ${rt.name} = ${policyId.resource.resourceTypeName}""".update().apply()
  }

  override def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit] = IO.unit
}

private final case class PolicyInfo(name: AccessPolicyName, resourceId: ResourceId, resourceTypeName: ResourceTypeName, email: WorkbenchEmail, public: Boolean)
