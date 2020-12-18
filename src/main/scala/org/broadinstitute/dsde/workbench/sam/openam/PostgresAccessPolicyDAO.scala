package org.broadinstitute.dsde.workbench.sam.openam

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.dao.{PostgresGroupDAO, SubGroupMemberTable}
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, PSQLStateExtensions, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

class PostgresAccessPolicyDAO(protected val dbRef: DbReference,
                              protected val ecForDatabaseIO: ExecutionContext)(implicit val cs: ContextShift[IO]) extends AccessPolicyDAO with DatabaseSupport with PostgresGroupDAO with LazyLogging {

  /**
    * Creates or updates all given resource types.
    *
    * This method obtains an EXCLUSIVE lock on the ResourceType table because if we boot multiple Sam instances at once,
    * they will all try to (re)create ResourceTypes at the same time and could collide. The lock is automatically
    * released at the end of the transaction.
    *
    * @param resourceTypes
    * @param samRequestContext
    * @return names of resource types actually created or updated
    */
  override def upsertResourceTypes(resourceTypes: Set[ResourceType], samRequestContext: SamRequestContext): IO[Set[ResourceTypeName]] = {
    if (resourceTypes.nonEmpty) {
      runInTransaction("upsertResourceTypes", samRequestContext) { implicit session =>
        samsql"lock table ${ResourceTypeTable.table} IN EXCLUSIVE MODE".execute().apply()

        val existingResourceTypes = loadResourceTypesInSession(resourceTypes.map(_.name))

        val changedResourceTypes = resourceTypes -- existingResourceTypes
        val changedResourceTypeNames = changedResourceTypes.map(_.name)
        if (changedResourceTypes.isEmpty) {
          logger.info("upsertResourceTypes: no changes, not updating")
        } else {
          logger.info(s"upsertResourceTypes: updating resource types [$changedResourceTypeNames]")
          upsertResourceTypes(resourceTypes)
          val resourceTypeNameToPKs = loadResourceTypePKsByName(resourceTypes)
          upsertActionPatterns(resourceTypes, resourceTypeNameToPKs)
          upsertRoles(resourceTypes, resourceTypeNameToPKs)
          insertActions(resourceTypes, resourceTypeNameToPKs)
          upsertRoleActions(resourceTypes, resourceTypeNameToPKs)
          upsertNestedRoles(resourceTypes, resourceTypeNameToPKs)
          logger.info(s"upsertResourceTypes: completed updates to resource types [$changedResourceTypeNames]")
        }
        changedResourceTypeNames
      }
    } else {
      IO.pure(Set.empty[ResourceTypeName])
    }
  }

  override def loadResourceTypes(resourceTypeNames: Set[ResourceTypeName], samRequestContext: SamRequestContext): IO[Set[ResourceType]] = {
    runInTransaction("loadResourceTypes", samRequestContext) { implicit session =>
      loadResourceTypesInSession(resourceTypeNames)
    }
  }

  private def loadResourceTypesInSession(resourceTypeNames: Set[ResourceTypeName])(implicit session: DBSession): Set[ResourceType] = {
    val (actionPatternRecords, resourceTypeRecords) = queryForResourceTypesAndActionPatterns(resourceTypeNames)
    if (resourceTypeRecords.nonEmpty) {
      val roleAndActionRecords = queryForResourceRolesAndActions(resourceTypeRecords)

      val resourceTypeToActionPatterns = unmarshalActionPatterns(actionPatternRecords)
      val resourceTypeToRoles = unmarshalResourceRoles(roleAndActionRecords)
      unmarshalResourceTypes(resourceTypeRecords, resourceTypeToActionPatterns, resourceTypeToRoles)
    } else {
      Set.empty
    }
  }

  private def queryForResourceRolesAndActions(resourceTypeRecords: Set[ResourceTypeRecord])(implicit session: DBSession) = {
    val rr = ResourceRoleTable.syntax("rr")
    val roleAction = RoleActionTable.syntax("roleAction")
    val ra = ResourceActionTable.syntax("ra")
    val nestedRole = NestedRoleTable.syntax("nestedRole")
    val nrr = ResourceRoleTable.syntax("nrr")
    val nrrt = ResourceTypeTable.syntax("nrrt")
    val roleQuery =
      samsql"""select ${rr.resultAll}, ${ra.resultAll}, ${nrr.resultAll}, ${nestedRole.result.descendantsOnly}, ${nrrt.result.name}
                 from ${ResourceRoleTable as rr}
                 left join ${RoleActionTable as roleAction} on ${roleAction.resourceRoleId} = ${rr.id}
                 left join ${ResourceActionTable as ra} on ${roleAction.resourceActionId} = ${ra.id}
                 left join ${NestedRoleTable as nestedRole} on ${nestedRole.baseRoleId} = ${rr.id}
                 left join ${ResourceRoleTable as nrr} on ${nestedRole.nestedRoleId} = ${nrr.id}
                 left join ${ResourceTypeTable as nrrt} on ${nrr.resourceTypeId} = ${nrrt.id}
                 where ${rr.resourceTypeId} in (${resourceTypeRecords.map(_.id)})
                 and ${rr.deprecated} = false"""

    roleQuery.map { rs =>
      val roleRecord = ResourceRoleTable(rr.resultName)(rs)
      val actionRecord = rs.anyOpt(ra.resultName.id).map(_ => ResourceActionTable(ra.resultName)(rs))
      val nestedRoleRecord = rs.anyOpt(nrr.resultName.id).map(_ => ResourceRoleTable(nrr.resultName)(rs))
      val descendantsOnly = rs.booleanOpt(nestedRole.resultName.descendantsOnly)
      val nestedRoleResourceType = rs.stringOpt(nrrt.resultName.name).map(ResourceTypeName.apply)
      (roleRecord, actionRecord, nestedRoleRecord, descendantsOnly, nestedRoleResourceType)
    }.list().apply()
  }

  private def queryForResourceTypesAndActionPatterns(resourceTypeNames: Iterable[ResourceTypeName])(implicit session: DBSession) = {
    val rt = ResourceTypeTable.syntax("rt")
    val rap = ResourceActionPatternTable.syntax("rap")
    val actionPatternQuery =
      samsql"""select ${rt.resultAll}, ${rap.resultAll}
                 from ${ResourceTypeTable as rt}
                 left join ${ResourceActionPatternTable as rap} on ${rap.resourceTypeId} = ${rt.id}
                 where ${rt.name} in (${resourceTypeNames})"""

    val (resourceTypeRecordsWithDups, actionPatternRecords) = actionPatternQuery.map { rs =>
      val resourceTypeRecord = ResourceTypeTable(rt.resultName)(rs)
      val actionPatternRecord = rs.anyOpt(rap.resultName.id).map(_ => ResourceActionPatternTable(rap.resultName)(rs))
      (resourceTypeRecord, actionPatternRecord)
    }.list().apply().unzip

    val resourceTypeRecords = resourceTypeRecordsWithDups.toSet
    (actionPatternRecords.flatten, resourceTypeRecords)
  }

  private def unmarshalResourceTypes(resourceTypeRecords: Set[ResourceTypeRecord], resourceTypeToActionPatterns: Map[ResourceTypePK, Set[ResourceActionPattern]], resourceTypeToRoles: Map[ResourceTypePK, Set[ResourceRole]]): Set[ResourceType] = {
    resourceTypeRecords.map { resourceTypeRecord =>
      ResourceType(
        resourceTypeRecord.name,
        resourceTypeToActionPatterns.getOrElse(resourceTypeRecord.id, Set.empty),
        resourceTypeToRoles.getOrElse(resourceTypeRecord.id, Set.empty),
        resourceTypeRecord.ownerRoleName,
        resourceTypeRecord.reuseIds
      )
    }
  }

  private def unmarshalActionPatterns(actionPatternRecords: List[ResourceActionPatternRecord]): Map[ResourceTypePK, Set[ResourceActionPattern]] = {
    actionPatternRecords.groupBy(_.resourceTypeId).map { case (resourceTypeId, actionPatternRecords) =>
      resourceTypeId -> actionPatternRecords.map(rec => ResourceActionPattern(rec.actionPattern.value, rec.description, rec.isAuthDomainConstrainable)).toSet
    }
  }

  private def unmarshalResourceRoles(rolesActionsAndNestedRolesRecords: List[(ResourceRoleRecord, Option[ResourceActionRecord], Option[ResourceRoleRecord], Option[Boolean], Option[ResourceTypeName])]): Map[ResourceTypePK, Set[ResourceRole]] = {
    rolesActionsAndNestedRolesRecords.groupBy { case (role, _, _, _, _) => role.resourceTypeId }.map { case (resourceTypeId, rolesActionsAndNestedRolesForResourceType) =>
      val roles = rolesActionsAndNestedRolesForResourceType.groupBy { case (role, _, _, _, _) => role.role }.map { case (roleName, actionsAndNestedRolesForRole) =>
        val roleActions = actionsAndNestedRolesForRole.flatMap { case (_, actionRec, _, _, _) => actionRec.map(_.action) }.toSet
        val (descendantRoles, includedRoles) = unmarshalNestedRoles(actionsAndNestedRolesForRole)

        ResourceRole(roleName, roleActions, includedRoles, descendantRoles)
      }
      resourceTypeId -> roles.toSet
    }
  }

  private def unmarshalNestedRoles(actionsAndNestedRolesByRole: List[(ResourceRoleRecord, Option[ResourceActionRecord], Option[ResourceRoleRecord], Option[Boolean], Option[ResourceTypeName])]): (Map[ResourceTypeName, Set[ResourceRoleName]], Set[ResourceRoleName]) = {
    val (maybeDescendantRoles, maybeIncludedRoles) = actionsAndNestedRolesByRole.collect { case (_, _, Some(nestedRoleRecord), Some(descendantsOnly), Some(resourceTypeName)) =>
      if (descendantsOnly) {
        (Option(resourceTypeName -> nestedRoleRecord.role), None)
      } else {
        (None, Option(nestedRoleRecord.role))
      }
    }.unzip
    val descendantRoles = maybeDescendantRoles.toSet.flatten.groupBy(_._1).view.mapValues(rolesWithResourceTypeNames => rolesWithResourceTypeNames.map(_._2)).toMap
    val includedRoles = maybeIncludedRoles.toSet.flatten

    (descendantRoles, includedRoles)
  }

  private def loadResourceTypePKsByName(resourceTypes: Iterable[ResourceType])(implicit session: DBSession): Map[ResourceTypeName, ResourceTypePK] = {
    val rt = ResourceTypeTable.syntax("rt")
    val query = samsql"""select ${rt.resultAll} from ${ResourceTypeTable as rt} where ${rt.name} in (${resourceTypes.map(_.name)})"""
    val resourceTypeRecords = query.map(ResourceTypeTable(rt.resultName)).list().apply()
    resourceTypeRecords.map(rec => rec.name -> rec.id).toMap
  }

  override def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType] = {
    upsertResourceTypes(Set(resourceType), samRequestContext).map(_ => resourceType)
  }

  private def upsertNestedRoles(resourceTypes: Iterable[ResourceType], resourceTypeNameToPKs: Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val includedRoles = for {
      rt <- resourceTypes
      baseRole <- rt.roles
      includedRole <- baseRole.includedRoles
    } yield {
      samsqls"(${resourceTypeNameToPKs(rt.name)}, ${baseRole.roleName}, ${resourceTypeNameToPKs(rt.name)}, ${includedRole}, false)"
    }
    val descendantRoles = for {
      rt <- resourceTypes
      baseRole <- rt.roles
      (descendantResourceType, descendantRoles) <- baseRole.descendantRoles
      descendantRole <- descendantRoles
    } yield {
      samsqls"(${resourceTypeNameToPKs(rt.name)}, ${baseRole.roleName}, ${resourceTypeNameToPKs(descendantResourceType)}, ${descendantRole}, true)"
    }
    val nestedRoles = includedRoles ++ descendantRoles

    val resourceRole = ResourceRoleTable.syntax("resourceRole")
    val nestedRole = NestedRoleTable.syntax("nestedRole")
    samsql"""delete from ${NestedRoleTable as nestedRole}
            using ${ResourceRoleTable as resourceRole}
            where ${nestedRole.baseRoleId} = ${resourceRole.id}
            and ${resourceRole.resourceTypeId} in (${resourceTypeNameToPKs.values})"""
      .update().apply()

    val nestedResourceRole = ResourceRoleTable.syntax("nestedResourceRole")
    val result = if (nestedRoles.nonEmpty) {
      val insertQuery =
        samsql"""
                insert into ${NestedRoleTable.table}(${NestedRoleTable.column.baseRoleId}, ${NestedRoleTable.column.nestedRoleId}, ${NestedRoleTable.column.descendantsOnly})
                select ${resourceRole.id}, ${nestedResourceRole.id}, insertValues.descendants_only from
                (values ${nestedRoles}) as insertValues (base_resource_type_id, base_role_name, nested_resource_type_id, nested_role_name, descendants_only)
                join ${ResourceRoleTable as resourceRole} on insertValues.base_role_name = ${resourceRole.role} and insertValues.base_resource_type_id = ${resourceRole.resourceTypeId}
                join ${ResourceRoleTable as nestedResourceRole} on insertValues.nested_role_name = ${nestedResourceRole.role} and insertValues.nested_resource_type_id = ${nestedResourceRole.resourceTypeId}"""
      insertQuery.update().apply()
    } else {
      0
    }
    samsql"""refresh materialized view ${FlattenedRoleMaterializedView.table}""".update().apply()
    result
  }

  private def upsertRoleActions(resourceTypes: Iterable[ResourceType], resourceTypeNameToPKs: Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val roleActions = for {
      rt <- resourceTypes
      role <- rt.roles
      action <- role.actions
    } yield {
      samsqls"(${resourceTypeNameToPKs(rt.name)}, ${role.roleName}, ${action})"
    }

    val resourceRole = ResourceRoleTable.syntax("resourceRole")
    val resourceAction = ResourceActionTable.syntax("resourceAction")
    val roleAction = RoleActionTable.syntax("roleAction")

    samsql"""delete from ${RoleActionTable as roleAction}
               using ${ResourceRoleTable as resourceRole}
               where ${roleAction.resourceRoleId} = ${resourceRole.id}
               and ${resourceRole.resourceTypeId} in (${resourceTypeNameToPKs.values})"""
      .update().apply()

    if (roleActions.nonEmpty) {
      val insertQuery =
        samsql"""insert into ${RoleActionTable.table}(${RoleActionTable.column.resourceRoleId}, ${RoleActionTable.column.resourceActionId})
               select ${resourceRole.id}, ${resourceAction.id} from
               (values $roleActions) AS insertValues (resource_type_id, role, action)
               join ${ResourceRoleTable as resourceRole} on insertValues.role = ${resourceRole.role} and insertValues.resource_type_id = ${resourceRole.resourceTypeId}
               join ${ResourceActionTable as resourceAction} on insertValues.action = ${resourceAction.action} and insertValues.resource_type_id = ${resourceAction.resourceTypeId}"""
      insertQuery.update().apply()
    } else {
      0
    }
  }

  private def upsertRoles(resourceTypes: Iterable[ResourceType], resourceTypeNameToPKs: Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val roleValues = for {
      rt <- resourceTypes
      role <- rt.roles
    } yield {
      samsqls"(${resourceTypeNameToPKs(rt.name)}, ${role.roleName})"
    }

    val resourceRoleColumn = ResourceRoleTable.column
    if (roleValues.nonEmpty) {
      samsql"""update ${ResourceRoleTable.table}
              set ${resourceRoleColumn.deprecated} = true
              where (${resourceRoleColumn.resourceTypeId}, ${resourceRoleColumn.role}) not in ($roleValues)
              and ${resourceRoleColumn.resourceTypeId} in (${resourceTypeNameToPKs.values})"""
        .update().apply()

      val insertRolesQuery =
        samsql"""insert into ${ResourceRoleTable.table}(${resourceRoleColumn.resourceTypeId}, ${resourceRoleColumn.role})
               values ${roleValues}
               on conflict do nothing"""

      insertRolesQuery.update().apply()
    } else {
      samsql"""update ${ResourceRoleTable.table}
              set ${resourceRoleColumn.deprecated} = true
              where ${resourceRoleColumn.resourceTypeId} in (${resourceTypeNameToPKs.values})"""
        .update().apply()
    }
  }

  private def insertActions(actions: Set[(ResourceAction, ResourceTypePK)])(implicit session: DBSession): Int = {
    if (actions.isEmpty) {
      0
    } else {
      val uniqueActionValues = actions.map { case (action, resourceTypePK) =>
        samsqls"(${resourceTypePK}, ${action})"
      }

      val insertActionQuery =
        samsql"""insert into ${ResourceActionTable.table}(${ResourceActionTable.column.resourceTypeId}, ${ResourceActionTable.column.action})
                    values ${uniqueActionValues}
                 on conflict do nothing"""

      insertActionQuery.update().apply()
    }
  }

  private def insertActions(resourceTypes: Iterable[ResourceType], resourceTypeNameToPKs: Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val actionsWithResourceTypePK = for {
      rt <- resourceTypes
      role <- rt.roles
      action <- role.actions
    } yield {
      (action, resourceTypeNameToPKs(rt.name))
    }

    insertActions(actionsWithResourceTypePK.toSet)
  }

  /**
    * Performs an UPSERT when a record already exists for this exact ActionPattern for all ResourceTypes.  Query will
    * rerwrite the `description` and `isAuthDomainConstrainable` columns if the ResourceTypeActionPattern already
    * exists.
    */
  private def upsertActionPatterns(resourceTypes: Iterable[ResourceType], resourceTypeNameToPKs: Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val rap = ResourceActionPatternTable.syntax("rap")
    val resourceActionPatternTableColumn = ResourceActionPatternTable.column

    val (keepers, upserts) = (for {
      resourceType <- resourceTypes
      actionPattern <- resourceType.actionPatterns
    } yield {
      val resourceTypePK = resourceTypeNameToPKs(resourceType.name)
      (samsqls"($resourceTypePK, ${actionPattern.value})",
        samsqls"(${resourceTypePK}, ${actionPattern.value}, ${actionPattern.description}, ${actionPattern.authDomainConstrainable})")
    }).unzip

    if (keepers.nonEmpty) {
      samsql"""delete from ${ResourceActionPatternTable as rap}
            where (${rap.resourceTypeId}, ${rap.actionPattern}) not in ($keepers)
            and ${rap.resourceTypeId} in (${resourceTypeNameToPKs.values})""".update().apply()
    } else {
      samsql"""delete from ${ResourceActionPatternTable as rap}
            where ${rap.resourceTypeId} in (${resourceTypeNameToPKs.values})""".update().apply()
    }
    if (upserts.nonEmpty) {
      val actionPatternQuery =
        samsql"""insert into ${ResourceActionPatternTable.table}
                  (${resourceActionPatternTableColumn.resourceTypeId},
          ${resourceActionPatternTableColumn.actionPattern},
          ${resourceActionPatternTableColumn.description},
          ${resourceActionPatternTableColumn.isAuthDomainConstrainable})
                  values ${upserts}
               on conflict (${resourceActionPatternTableColumn.resourceTypeId}, ${resourceActionPatternTableColumn.actionPattern})
                  do update
                      set ${resourceActionPatternTableColumn.description} = EXCLUDED.${resourceActionPatternTableColumn.description},
          ${resourceActionPatternTableColumn.isAuthDomainConstrainable} = EXCLUDED.${resourceActionPatternTableColumn.isAuthDomainConstrainable}"""
      actionPatternQuery.update().apply()
    } else {
      0
    }
  }

  private def upsertResourceTypes(resourceTypes: Iterable[ResourceType])(implicit session: DBSession): Int = {
    val resourceTypeTableColumn = ResourceTypeTable.column
    val resourceTypeValues = resourceTypes.map(rt => samsqls"""(${rt.name}, ${rt.ownerRoleName}, ${rt.reuseIds})""")
    samsql"""insert into ${ResourceTypeTable.table} (${resourceTypeTableColumn.name}, ${resourceTypeTableColumn.ownerRoleName}, ${resourceTypeTableColumn.reuseIds})
               values $resourceTypeValues
             on conflict (${ResourceTypeTable.column.name})
               do update
                 set ${resourceTypeTableColumn.ownerRoleName} = EXCLUDED.${resourceTypeTableColumn.ownerRoleName},
                 ${resourceTypeTableColumn.reuseIds} = EXCLUDED.${resourceTypeTableColumn.reuseIds}""".update().apply()
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
    val insertResourceQuery = resource.parent match {
      case None =>
        samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId})
               values (${resource.resourceId}, (${loadResourceTypePK(resource.resourceTypeName)}))"""
      case Some(parentId) =>
        // loading parent PK first so we can ensure it exists instead of blindly inserting a possible null
        // this was probably already checked when checking access to parent so if we have gotten this far it is an internal error
        val parentPK = loadResourcePK(parentId)
        // note that when setting the parent we are not checking for circular hierarchies but that should be ok
        // since this is a new resource and should not be a parent of another so no circles can be possible
        samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId}, ${resourceTableColumn.resourceParentId})
               values (${resource.resourceId}, (${loadResourceTypePK(resource.resourceTypeName)}), $parentPK)"""
    }

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

  /**
    * Queries the database for the PK of the resource and throws an error if it does not exist
    * @param resourceId
    * @return
    */
  private def loadResourcePK(resourceId: FullyQualifiedResourceId)(implicit session: DBSession): ResourcePK = {
    val prt = ResourceTypeTable.syntax("prt")
    val pr = ResourceTable.syntax("pr")
    val loadResourcePKQuery =
      samsql"""select ${pr.result.id}
              | from ${ResourceTable as pr}
              | join ${ResourceTypeTable as prt} on ${prt.id} = ${pr.resourceTypeId}
              | where ${pr.name} = ${resourceId.resourceId}
              | and ${prt.name} = ${resourceId.resourceTypeName}""".stripMargin

    loadResourcePKQuery.map(rs => ResourcePK(rs.long(pr.resultName.id))).single().apply().getOrElse(
      throw new WorkbenchException(s"resource $resourceId not found")
    )
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

      insertPolicyRoles(FullyQualifiedResourceRole.fullyQualify(policy.roles, policy.id.resource.resourceTypeName), policyId, false)
      insertPolicyRoles(policy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceRole.fullyQualify(permissions.roles, permissions.resourceType)), policyId, true)
      insertPolicyActions(FullyQualifiedResourceAction.fullyQualify(policy.actions, policy.id.resource.resourceTypeName), policyId, false)
      insertPolicyActions(policy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceAction.fullyQualify(permissions.actions, permissions.resourceType)), policyId, true)

      policy
    }).recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"policy $policy already exists")))
    }
  }

  private def insertPolicyActions(actions: Set[FullyQualifiedResourceAction], policyId: PolicyPK, descendantsOnly: Boolean)(implicit session: DBSession): Int = {
    val ra = ResourceActionTable.syntax("ra")
    val rt = ResourceTypeTable.syntax("rt")
    val paCol = PolicyActionTable.column
    if (actions.nonEmpty) {
      val actionsWithResourceTypeSql = actions.map {
        case FullyQualifiedResourceAction(action, resourceType) => samsqls"(${action}, ${resourceType})"
      }
      val insertQuery = samsqls"""insert into ${PolicyActionTable.table} (${paCol.resourcePolicyId}, ${paCol.resourceActionId}, ${paCol.descendantsOnly})
            select ${policyId}, ${ra.result.id}, ${descendantsOnly}
            from ${ResourceActionTable as ra}
            join ${ResourceTypeTable as rt} on ${ra.resourceTypeId} = ${rt.id}
            where (${ra.action}, ${rt.name}) in (${actionsWithResourceTypeSql})"""

      val inserted = samsql"$insertQuery".update().apply()

      if (inserted != actions.size) {
        // in this case some actions that we want to insert did not exist in ResourceActionTable
        // add them now and rerun the insert ignoring conflicts
        // this case should happen rarely
        import SamTypeBinders._
        val resourceTypeNames = actions.map(_.resourceTypeName)

        val resourceTypeNameAndPK = samsql"select ${rt.result.name}, ${rt.result.id} from ${ResourceTypeTable as rt} where (${rt.name}) in (${resourceTypeNames})"
          .map(rs => (rs.get[ResourceTypeName](rt.resultName.name), rs.get[ResourceTypePK](rt.resultName.id))).list().apply().toMap
        val actionsWithResourceTypePK = actions.map {
          case FullyQualifiedResourceAction(action, resourceTypeName) =>
            (action, resourceTypeNameAndPK.getOrElse(resourceTypeName,
              throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource type ${resourceTypeName} not found"))))
        }
        insertActions(actionsWithResourceTypePK)

        val moreInserted = samsql"$insertQuery on conflict do nothing".update().apply()

        inserted + moreInserted
      } else {
        inserted
      }
    } else {
      0
    }
  }

  private def insertPolicyRoles(roles: Set[FullyQualifiedResourceRole], policyId: PolicyPK, descendantsOnly: Boolean)(implicit session: DBSession): Int = {
    val rr = ResourceRoleTable.syntax("rr")
    val rt = ResourceTypeTable.syntax("rt")
    val prCol = PolicyRoleTable.column
    if (roles.nonEmpty) {
      val rolesWithResourceTypeSql = roles.map {
        case FullyQualifiedResourceRole(role, resourceType) => samsqls"(${role}, ${resourceType})"
      }
      val insertedRolesCount = samsql"""insert into ${PolicyRoleTable.table} (${prCol.resourcePolicyId}, ${prCol.resourceRoleId}, ${prCol.descendantsOnly})
            select ${policyId}, ${rr.result.id}, ${descendantsOnly}
            from ${ResourceRoleTable as rr}
            join ${ResourceTypeTable as rt} on ${rr.resourceTypeId} = ${rt.id}
            where (${rr.role}, ${rt.name}) in (${rolesWithResourceTypeSql})
            and ${rr.deprecated} = false"""
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
      overwritePolicyRolesInternal(newPolicy.id, newPolicy.roles, newPolicy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceRole.fullyQualify(permissions.roles, permissions.resourceType)))
      overwritePolicyActionsInternal(newPolicy.id, newPolicy.actions, newPolicy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceAction.fullyQualify(permissions.actions, permissions.resourceType)))
      setPolicyIsPublicInternal(newPolicy.id, newPolicy.public)

      newPolicy
    })
  }

  private def overwritePolicyRolesInternal(id: FullyQualifiedPolicyId, roles: Set[ResourceRoleName], descendantRoles: Set[FullyQualifiedResourceRole])(implicit session: DBSession): Int = {
    val policyPK = getPolicyPK(id)

    val pr = PolicyRoleTable.syntax("pr")
    samsql"delete from ${PolicyRoleTable as pr} where ${pr.resourcePolicyId} = $policyPK".update().apply()

    insertPolicyRoles(FullyQualifiedResourceRole.fullyQualify(roles, id.resource.resourceTypeName), policyPK, false)
    insertPolicyRoles(descendantRoles, policyPK, true)
  }

  private def overwritePolicyActionsInternal(id: FullyQualifiedPolicyId, actions: Set[ResourceAction], descendantActions: Set[FullyQualifiedResourceAction])(implicit session: DBSession): Int = {
    val policyPK = getPolicyPK(id)

    val pa = PolicyActionTable.syntax("pa")
    samsql"delete from ${PolicyActionTable as pa} where ${pa.resourcePolicyId} = $policyPK".update().apply()

    insertPolicyActions(FullyQualifiedResourceAction.fullyQualify(actions, id.resource.resourceTypeName), policyPK, false)
    insertPolicyActions(descendantActions, policyPK, true)
  }

  private def getPolicyPK(id: FullyQualifiedPolicyId)(implicit session: DBSession): PolicyPK = {
    val p = PolicyTable.syntax("p")
    samsql"select ${p.id} from ${PolicyTable as p} where ${p.name} = ${id.accessPolicyName} and ${p.resourceId} = (${ResourceTable.loadResourcePK(id.resource)})".map(rs => PolicyPK(rs.long(1))).single().apply().getOrElse {
      throw new WorkbenchException(s"policy record not found for $id")
    }
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[LazyList[ResourceIdAndPolicyName]] = {
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
      query.map(rs => ResourceIdAndPolicyName(rs.get[ResourceId](r.resultName.name), rs.get[AccessPolicyName](p.resultName.name))).list().apply().to(LazyList)
    })
  }

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithoutMembers]] = {
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
      }.to(LazyList)
    })
  }

  // Abstracts logic to load and unmarshal one or more policies, use to get full AccessPolicy objects from Postgres
  private def listPolicies(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName] = None, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicy]] = {
    val g = GroupTable.syntax("g")
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val gm = GroupMemberTable.syntax("gm")
    val sg = GroupTable.syntax("sg")
    val p = PolicyTable.syntax("p")
    val sp = PolicyTable.syntax("sp")
    val sr = ResourceTable.syntax("sr")
    val srt = ResourceTypeTable.syntax("srt")
    val pr = PolicyRoleTable.syntax("pr")
    val rr = ResourceRoleTable.syntax("rr")
    val pa = PolicyActionTable.syntax("pa")
    val ra = ResourceActionTable.syntax("ra")
    val prrt = ResourceTypeTable.syntax("prrt")
    val part = ResourceTypeTable.syntax("part")

    val limitOnePolicyClause: SQLSyntax = limitOnePolicy match {
      case Some(policyName) => samsqls"and ${p.name} = ${policyName}"
      case None => samsqls""
    }

    val listPoliciesQuery =
      samsql"""select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${gm.result.memberUserId}, ${sg.result.name}, ${sp.result.name}, ${sr.result.name}, ${srt.result.name}, ${prrt.result.name}, ${rr.result.role}, ${pr.result.descendantsOnly}, ${part.result.name}, ${ra.result.action}, ${pa.result.descendantsOnly}
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
          left join ${ResourceTypeTable as prrt} on ${rr.resourceTypeId} = ${prrt.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          left join ${ResourceTypeTable as part} on ${ra.resourceTypeId} = ${part.id}
          where ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}
          ${limitOnePolicyClause}"""

    import SamTypeBinders._
    runInTransaction("listPolicies", samRequestContext)({ implicit session =>
      val results = listPoliciesQuery.map(rs => (PolicyInfo(rs.get[AccessPolicyName](p.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[ResourceTypeName](rt.resultName.name), rs.get[WorkbenchEmail](g.resultName.email), rs.boolean(p.resultName.public)),
        MemberResult(rs.stringOpt(gm.resultName.memberUserId).map(WorkbenchUserId), rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName), rs.stringOpt(sp.resultName.name).map(AccessPolicyName(_)), rs.stringOpt(sr.resultName.name).map(ResourceId(_)), rs.stringOpt(srt.resultName.name).map(ResourceTypeName(_))),
        (RoleResult(rs.stringOpt(prrt.resultName.name).map(ResourceTypeName(_)), rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)), rs.booleanOpt(pr.resultName.descendantsOnly)),
          ActionResult(rs.stringOpt(part.resultName.name).map(ResourceTypeName(_)), rs.stringOpt(ra.resultName.action).map(ResourceAction(_)), rs.booleanOpt(pa.resultName.descendantsOnly)))))
        .list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, memberResults, permissionsResults) = resultsByPolicy.unzip3

        val policyMembers = unmarshalPolicyMembers(memberResults)

        val (policyRoles, policyActions, policyDescendantPermissions) = unmarshalPolicyPermissions(permissionsResults)

        AccessPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name), policyMembers, policyInfo.email, policyRoles, policyActions, policyDescendantPermissions, policyInfo.public)
      }.to(LazyList)
    })
  }



  private def unmarshalPolicyMembers(memberResults: List[MemberResult]): Set[WorkbenchSubject] = {
    memberResults.collect {
      case MemberResult(Some(userId), None, None, None, None) => userId
      case MemberResult(None, Some(groupName), None, None, None) => groupName
      case MemberResult(None, Some(_), Some(policyName), Some(resourceName), Some(resourceTypeName)) => FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceName), policyName)
    }.toSet
  }

  private def unmarshalPolicyPermissions(permissionsResults: List[(RoleResult, ActionResult)]): (Set[ResourceRoleName], Set[ResourceAction], Set[AccessPolicyDescendantPermissions]) = {
    val (roleResults, actionResults) = permissionsResults.unzip
    val (descendantRoleResults, topLevelRoleResults) = roleResults.partition(_.descendantsOnly.getOrElse(false))
    val (descendantActionResults, topLevelActionResults) = actionResults.partition(_.descendantsOnly.getOrElse(false))

    val roles = topLevelRoleResults.collect { case RoleResult(_, Some(resourceRoleName), _) => resourceRoleName }.toSet
    val actions = topLevelActionResults.collect { case ActionResult(_, Some(resourceActionName), _) => resourceActionName }.toSet

    val descendantPermissions = descendantRoleResults.groupBy(_.resourceTypeName).collect { case (Some(descendantResourceTypeName), descendantRolesWithResourceType) =>
      val descendantRoles = descendantRolesWithResourceType.collect { case RoleResult(_, Some(role), _) => role }
      val descendantActions = descendantActionResults.groupBy(_.resourceTypeName).getOrElse(Option(descendantResourceTypeName), List.empty).collect { case ActionResult(_, Some(action), _) => action }
      AccessPolicyDescendantPermissions(descendantResourceTypeName, descendantActions.toSet, descendantRoles.toSet)
    }.toSet

    (roles, actions, descendantPermissions)
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

  override def listUserResourcesWithRolesAndActions(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Iterable[ResourceIdWithRolesAndActions]] = {
    runInTransaction("listUserResourcesWithRolesAndActions", samRequestContext)({ implicit session =>
      val userPoliciesCommonTableExpression = userPoliciesForResourceTypeCommonTableExpressions(resourceTypeName, userId)

      val userResourcePolicyTable = userPoliciesCommonTableExpression.userResourcePolicyTable
      val userResourcePolicy = userPoliciesCommonTableExpression.userResourcePolicy
      val cteQueryFragment = userPoliciesCommonTableExpression.queryFragment

      val policyRole = PolicyRoleTable.syntax("policyRole")
      val resourceRole = ResourceRoleTable.syntax("resourceRole")
      val flattenedRole = FlattenedRoleMaterializedView.syntax("flattenedRole")
      val policyActionJoin = PolicyActionTable.syntax("policyActionJoin")
      val policyAction = ResourceActionTable.syntax("policyAction")

      val listUserResourcesQuery = samsql"""$cteQueryFragment
        select ${userResourcePolicy.result.baseResourceName}, ${resourceRole.result.role}, ${policyAction.result.action}, ${userResourcePolicy.result.public}, ${userResourcePolicy.result.inherited}
          from ${userResourcePolicyTable as userResourcePolicy}
          left join ${PolicyRoleTable as policyRole} on ${userResourcePolicy.policyId} = ${policyRole.resourcePolicyId}
          left join ${FlattenedRoleMaterializedView as flattenedRole} on ${policyRole.resourceRoleId} = ${flattenedRole.baseRoleId} and ${roleAppliesToResource(userResourcePolicy, policyRole, flattenedRole)}
          left join ${ResourceRoleTable as resourceRole} on ${flattenedRole.nestedRoleId} = ${resourceRole.id} and ${userResourcePolicy.baseResourceTypeId} = ${resourceRole.resourceTypeId}
          left join ${PolicyActionTable as policyActionJoin} on ${userResourcePolicy.policyId} = ${policyActionJoin.resourcePolicyId} and ${userResourcePolicy.inherited} = ${policyActionJoin.descendantsOnly}
          left join ${ResourceActionTable as policyAction} on ${policyActionJoin.resourceActionId} = ${policyAction.id} and ${userResourcePolicy.baseResourceTypeId} = ${policyAction.resourceTypeId}
          where ${resourceRole.role} is not null or ${policyAction.action} is not null"""

      val queryResults = listUserResourcesQuery.map { rs =>
        val rolesAndActions = RolesAndActions(rs.stringOpt(resourceRole.resultName.role).toSet.map(ResourceRoleName(_)), rs.stringOpt(policyAction.resultName.action).toSet.map(ResourceAction(_)))
        val public = rs.boolean(userResourcePolicy.resultName.public)
        val inherited = rs.boolean(userResourcePolicy.resultName.inherited)
        ResourceIdWithRolesAndActions(
          ResourceId(rs.string(userResourcePolicy.resultName.baseResourceName)),
          if (!inherited) rolesAndActions else RolesAndActions.empty,
          if (inherited) rolesAndActions else RolesAndActions.empty,
          if (public) rolesAndActions else RolesAndActions.empty
        )
      }.list().apply()

      aggregateByResource(queryResults)
    })
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicy]] = {
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


  /**
    *
    * Determining whether a role should or should not apply to a resource is a bit more complicated than it initially
    * appears. This logic is shared across the three queries (listUserResourceActions, listUserResourceRoles,
    * listUserResourcesWithRolesAndActions) that search a resource's hierarchy for all of the relevant roles and actions
    * that a user has on said resource. The following truth table shows the desired behavior of this SQL fragment where
    * result indicates whether a given role does or does not apply to the resource
    *
    * userResourcePolicy.inherited  |  policyRole.descendantsOnly  |  flattenedRole.descendantsOnly  |  result
    *            T                  |             T                |                T                |    T
    *            T                  |             T                |                F                |    T
    *            T                  |             F                |                T                |    T
    *            T                  |             F                |                F                |    F
    *            F                  |             T                |                T                |    F
    *            F                  |             T                |                F                |    F
    *            F                  |             F                |                T                |    F
    *            F                  |             F                |                F                |    T
    *
    */
  private def roleAppliesToResource(userResourcePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[UserResourcePolicyRecord], UserResourcePolicyRecord],
                                    policyRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRoleRecord], PolicyRoleRecord],
                                    flattenedRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlattenedRoleRecord], FlattenedRoleRecord]): SQLSyntax = {
    samsqls"""((${userResourcePolicy.inherited} and (${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))
             or not (${userResourcePolicy.inherited} or ${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))"""
  }

  override def listUserResourceActions(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceAction]] = {
    runInTransaction("listUserResourceActions", samRequestContext)({ implicit session =>
      val userPoliciesCommonTableExpression = userPoliciesOnResourceCommonTableExpressions(resourceId, user)

      val userResourcePolicyTable = userPoliciesCommonTableExpression.userResourcePolicyTable
      val userResourcePolicy = userPoliciesCommonTableExpression.userResourcePolicy
      val cteQueryFragment = userPoliciesCommonTableExpression.queryFragment

      val policyRole = PolicyRoleTable.syntax("policyRole")
      val resourceRole = ResourceRoleTable.syntax("resourceRole")
      val flattenedRole = FlattenedRoleMaterializedView.syntax("flattenedRole")
      val policyActionJoin = PolicyActionTable.syntax("policyActionJoin")
      val policyAction = ResourceActionTable.syntax("policyAction")
      val roleActionJoin = RoleActionTable.syntax("roleActionJoin")
      val roleAction = ResourceActionTable.syntax("roleAction")

      val listUserResourceActionsQuery = samsql"""$cteQueryFragment
        select ${roleAction.action} as action
          from ${userResourcePolicyTable as userResourcePolicy}
          join ${PolicyRoleTable as policyRole} on ${userResourcePolicy.policyId} = ${policyRole.resourcePolicyId}
          join ${FlattenedRoleMaterializedView as flattenedRole} on ${policyRole.resourceRoleId} = ${flattenedRole.baseRoleId}
          join ${ResourceRoleTable as resourceRole} on ${flattenedRole.nestedRoleId} = ${resourceRole.id} and ${userResourcePolicy.baseResourceTypeId} = ${resourceRole.resourceTypeId}
          join ${RoleActionTable as roleActionJoin} on ${resourceRole.id} = ${roleActionJoin.resourceRoleId}
          join ${ResourceActionTable as roleAction} on ${roleActionJoin.resourceActionId} = ${roleAction.id}
          where ${roleAppliesToResource(userResourcePolicy, policyRole, flattenedRole)}
        union
        select ${policyAction.action} as action
          from ${userResourcePolicyTable as userResourcePolicy}
          join ${PolicyActionTable as policyActionJoin} on ${userResourcePolicy.policyId} = ${policyActionJoin.resourcePolicyId} and ${userResourcePolicy.inherited} = ${policyActionJoin.descendantsOnly}
          join ${ResourceActionTable as policyAction} on ${policyActionJoin.resourceActionId} = ${policyAction.id} and ${userResourcePolicy.baseResourceTypeId} = ${policyAction.resourceTypeId}"""

      listUserResourceActionsQuery.map(rs => ResourceAction(rs.string("action"))).list().apply().toSet
    })
  }

  override def listUserResourceRoles(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceRoleName]] = {
    runInTransaction("listUserResourceRoles", samRequestContext)({ implicit session =>
      val userPoliciesCommonTableExpression = userPoliciesOnResourceCommonTableExpressions(resourceId, user)

      val userResourcePolicyTable = userPoliciesCommonTableExpression.userResourcePolicyTable
      val userResourcePolicy = userPoliciesCommonTableExpression.userResourcePolicy
      val cteQueryFragment = userPoliciesCommonTableExpression.queryFragment

      val policyRole = PolicyRoleTable.syntax("policyRole")
      val resourceRole = ResourceRoleTable.syntax("resourceRole")
      val flattenedRole = FlattenedRoleMaterializedView.syntax("flattenedRole")
      val listUserResourceRolesQuery = samsql"""$cteQueryFragment
        select ${resourceRole.result.role}
          from ${userResourcePolicyTable as userResourcePolicy}
          join ${PolicyRoleTable as policyRole} on ${userResourcePolicy.policyId} = ${policyRole.resourcePolicyId}
          join ${FlattenedRoleMaterializedView as flattenedRole} on ${policyRole.resourceRoleId} = ${flattenedRole.baseRoleId}
          join ${ResourceRoleTable as resourceRole} on ${flattenedRole.nestedRoleId} = ${resourceRole.id} and ${userResourcePolicy.baseResourceTypeId} = ${resourceRole.resourceTypeId}
          where ${roleAppliesToResource(userResourcePolicy, policyRole, flattenedRole)}"""

      listUserResourceRolesQuery.map(rs => ResourceRoleName(rs.string(resourceRole.resultName.role))).list().apply().toSet
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

  override def getResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]] = {
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val pr = ResourceTable.syntax("pr")
    val prt = ResourceTypeTable.syntax("prt")

    runInTransaction("getResourceParent", samRequestContext)({ implicit session =>
      val query = samsql"""select ${pr.result.name}, ${prt.result.name}
              from ${ResourceTable as r}
              join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              join ${ResourceTable as pr} on ${pr.id} = ${r.resourceParentId}
              join ${ResourceTypeTable as prt} on ${pr.resourceTypeId} = ${prt.id}
              where ${r.name} = ${resource.resourceId}
              and ${rt.name} = ${resource.resourceTypeName}"""

      import SamTypeBinders._
      query.map(rs =>
        FullyQualifiedResourceId(
          rs.get[ResourceTypeName](prt.resultName.name),
          rs.get[ResourceId](pr.resultName.name)))
        .single().apply()
    })
  }

  /** We need to make sure that we aren't introducing any cyclical resource hierarchies, so when we try to set the
    * parent of a resource, we first lookup all of the ancestors of the potential new parent to make sure that the
    * new child resource is not already an ancestor of the new parent */
  override def setResourceParent(childResource: FullyQualifiedResourceId, parentResource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")
    val arColumn = ancestorResourceTable.column

    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val pr = ResourceTable.syntax("pr")
    val resourceTableColumn = ResourceTable.column

    runInTransaction("setResourceParent", samRequestContext)({ implicit session =>
      val parentResourcePK = loadResourcePK(parentResource)

      val query =
        samsql"""with recursive ${ancestorResourceTable.table}(${arColumn.resourceId}) as (
                  select ${r.id}
                  from ${ResourceTable as r}
                  join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                  where ${r.name} = ${parentResource.resourceId}
                  and ${rt.name} = ${parentResource.resourceTypeName}
                  union
                  select ${pr.resourceParentId}
                  from ${ResourceTable as pr}
                  join ${ancestorResourceTable as ar} on ${ar.resourceId} = ${pr.id}
                  where ${pr.resourceParentId} is not null
        ) update ${ResourceTable as r}
          set ${resourceTableColumn.resourceParentId} = $parentResourcePK
          from ${ResourceTypeTable as rt}
          where ${rt.id} = ${r.resourceTypeId}
          and ${r.name} = ${childResource.resourceId}
          and ${rt.name} = ${childResource.resourceTypeName}
          and ${r.id} not in
              ( select ${ar.resourceId}
                from ${ancestorResourceTable as ar} )"""

      if (query.update().apply() != 1) {
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.BadRequest, "Cannot set parent as this would introduce a cyclical resource hierarchy")
        )
      }
    })
  }

  override def deleteResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean] = {
    val r = ResourceTable.syntax("r")
    val resourceTableColumn = ResourceTable.column
    val rt = ResourceTypeTable.syntax("rt")

    runInTransaction("deleteResourceParent", samRequestContext)({ implicit session =>
      val query =
        samsql"""update ${ResourceTable as r}
          set ${resourceTableColumn.resourceParentId} = null
          from ${ResourceTypeTable as rt}
          where ${rt.id} = ${r.resourceTypeId}
          and ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}"""

      query.update().apply() > 0
    })
  }

  override def listResourceChildren(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[FullyQualifiedResourceId]] = {
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val cr = ResourceTable.syntax("cr")
    val crt = ResourceTypeTable.syntax("crt")

    val query =
      samsql"""
         select ${cr.result.name}, ${crt.result.name}
         from ${ResourceTable as r}
         join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
         join ${ResourceTable as cr} on ${r.id} = ${cr.resourceParentId}
         join ${ResourceTypeTable as crt} on ${cr.resourceTypeId} = ${crt.id}
         where ${r.name} = ${resource.resourceId}
         and ${rt.name} = ${resource.resourceTypeName}"""

    runInTransaction("getResourceChildren", samRequestContext)({ implicit session =>
      import SamTypeBinders._

      query.map(rs =>
        FullyQualifiedResourceId(
          rs.get[ResourceTypeName](crt.resultName.name),
          rs.get[ResourceId](cr.resultName.name)))
        .list().apply().toSet
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

  /**
    * Return type of userPoliciesCommonTableExpressions
    * @param userResourcePolicyTable scalikejdbc table object used reference to policy table created in the CTE
    * @param userResourcePolicy scalikejdbc syntax provider used reference to policy table created in the CTE
    * @param queryFragment the query fragment to start your query
    */
  case class UserPoliciesCommonTableExpression(userResourcePolicyTable: UserResourcePolicyTable, userResourcePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[UserResourcePolicyRecord], UserResourcePolicyRecord], queryFragment: SQLSyntax)

  private def userPoliciesOnResourceCommonTableExpressions(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId): UserPoliciesCommonTableExpression = {
    userPoliciesCommonTableExpressions(resourceId.resourceTypeName, Option(resourceId.resourceId), user)
  }

  private def userPoliciesForResourceTypeCommonTableExpressions(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): UserPoliciesCommonTableExpression = {
    userPoliciesCommonTableExpressions(resourceTypeName, None, user)
  }

  /**
    * Produces the Common Table Expression query fragment that is the basis for queries of policies on a resource
    * accessible by a user. This takes into account the group membership hierarchy of the user and the resource
    * ancestry.
    *
    * There are 3 queries involved in the CTE:
    * 1) unroll the groups the user is a member of
    * 2) unroll the ancestry of the resource
    * 3) get all the policies a user is a member of on the resource or any of its ancestor (this also carries along
    * whether or not the policy is inherited and the id of the type of the resource)
    *
    * @param resourceTypeName the name of the resource type
    * @param resourceId the id of the resource being queried, if None all resources of resourceTypeName will be queried
    * @param user the id of the user being queried
    * @return
    */
  private def userPoliciesCommonTableExpressions(resourceTypeName: ResourceTypeName, resourceId: Option[ResourceId], user: WorkbenchUserId): UserPoliciesCommonTableExpression = {
    val ancestorGroupsTable = SubGroupMemberTable("ancestor_groups")
    val ancestorGroup = ancestorGroupsTable.syntax("ancestorGroup")
    val agColumn = ancestorGroupsTable.column

    val parentGroup = GroupMemberTable.syntax("parent_groups")
    val resource = ResourceTable.syntax("resource")
    val resourceType = ResourceTypeTable.syntax("resourceType")
    val groupMember = GroupMemberTable.syntax("groupMember")
    val policy = PolicyTable.syntax("policy")

    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")
    val arColumn = ancestorResourceTable.column
    val parentResource = ResourceTable.syntax("parentResource")

    val userResourcePolicyTable = UserResourcePolicyTable("user_resource_policy")
    val userResourcePolicy = userResourcePolicyTable.syntax("userResourcePolicy")
    val urpColumn = userResourcePolicyTable.column

    val resourceIdFragment = resourceId.map(id => samsqls"and ${resource.name} = ${id}").getOrElse(samsqls"")

    val queryFragment =
      samsqls"""with recursive
        ${ancestorGroupsTable.table}(${agColumn.parentGroupId}) as (
          select ${groupMember.groupId}
          from ${GroupMemberTable as groupMember}
          where ${groupMember.memberUserId} = ${user}
          union
          select ${parentGroup.groupId}
          from ${GroupMemberTable as parentGroup}
          join ${ancestorGroupsTable as ancestorGroup} on ${agColumn.parentGroupId} = ${parentGroup.memberGroupId}),

        ${ancestorResourceTable.table}(${arColumn.resourceId}, ${arColumn.isAncestor}, ${arColumn.baseResourceTypeId}, ${arColumn.baseResourceName}) as (
          select ${resource.id}, false, ${resourceType.id}, ${resource.name}
          from ${ResourceTable as resource}
          join ${ResourceTypeTable as resourceType} on ${resource.resourceTypeId} = ${resourceType.id}
          where ${resourceType.name} = ${resourceTypeName}
          ${resourceIdFragment}
          union
          select ${parentResource.resourceParentId}, true, ${ancestorResource.baseResourceTypeId}, ${ancestorResource.baseResourceName}
          from ${ResourceTable as parentResource}
          join ${ancestorResourceTable as ancestorResource} on ${ancestorResource.resourceId} = ${parentResource.id}
          where ${parentResource.resourceParentId} is not null),

        ${userResourcePolicyTable.table}(${urpColumn.policyId}, ${urpColumn.baseResourceTypeId}, ${urpColumn.baseResourceName}, ${urpColumn.inherited}, ${urpColumn.public}) as (
          select ${policy.id}, ${ancestorResource.baseResourceTypeId}, ${ancestorResource.baseResourceName}, ${ancestorResource.isAncestor}, ${policy.public}
          from ${ancestorResourceTable as ancestorResource}
          join ${PolicyTable as policy} on ${policy.resourceId} = ${ancestorResource.resourceId}
          where ${policy.public} OR ${policy.groupId} in (select ${ancestorGroup.parentGroupId} from ${ancestorGroupsTable as ancestorGroup}))"""

    UserPoliciesCommonTableExpression(userResourcePolicyTable, userResourcePolicy, queryFragment)
  }
}

private final case class PolicyInfo(name: AccessPolicyName, resourceId: ResourceId, resourceTypeName: ResourceTypeName, email: WorkbenchEmail, public: Boolean)
private final case class MemberResult(userId: Option[WorkbenchUserId], groupName: Option[WorkbenchGroupName], policyName: Option[AccessPolicyName], resourceId: Option[ResourceId], resourceTypeName: Option[ResourceTypeName])
private final case class RoleResult(resourceTypeName: Option[ResourceTypeName], role: Option[ResourceRoleName], descendantsOnly: Option[Boolean])
private final case class ActionResult(resourceTypeName: Option[ResourceTypeName], action: Option[ResourceAction], descendantsOnly: Option[Boolean])

final case class FullyQualifiedResourceRole(roleName: ResourceRoleName, resourceTypeName: ResourceTypeName)
object FullyQualifiedResourceRole {
  def fullyQualify(roles: Set[ResourceRoleName], resourceTypeName: ResourceTypeName): Set[FullyQualifiedResourceRole] =
    roles.map(FullyQualifiedResourceRole(_, resourceTypeName))
}

final case class FullyQualifiedResourceAction(action: ResourceAction, resourceTypeName: ResourceTypeName)
object FullyQualifiedResourceAction {
  def fullyQualify(actions: Set[ResourceAction], resourceTypeName: ResourceTypeName): Set[FullyQualifiedResourceAction] =
    actions.map(FullyQualifiedResourceAction(_, resourceTypeName))
}

// these 2 case classes represent the logical table used in recursive ancestor resource queries
// this table does not actually exist but looks like a table in a WITH RECURSIVE query
final case class AncestorResourceRecord(resourceId: ResourcePK, isAncestor: Boolean, baseResourceTypeId: ResourceTypePK, baseResourceName: ResourceId)
final case class AncestorResourceTable(override val tableName: String) extends SQLSyntaxSupport[AncestorResourceRecord] {
  // need to specify column names explicitly because this table does not actually exist in the database
  override val columnNames: Seq[String] = Seq("resource_id", "is_ancestor", "base_resource_type_id", "base_resource_name")
}

// these 2 case classes represent the logical table used in policy queries that take into account user group
// membership and policies inherited from ancestor resources
// this table does not actually exist but looks like a table in a WITH query
final case class UserResourcePolicyRecord(policyId: PolicyPK, baseResourceTypeId: ResourceTypePK, baseResourceName: ResourceId, inherited: Boolean, public: Boolean)
final case class UserResourcePolicyTable(override val tableName: String) extends SQLSyntaxSupport[UserResourcePolicyRecord] {
  // need to specify column names explicitly because this table does not actually exist in the database
  override val columnNames: Seq[String] = Seq("policy_id", "inherited", "base_resource_type_id", "public", "base_resource_name")
}
