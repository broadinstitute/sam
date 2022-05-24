package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.time.Instant
import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.dataAccess.LoadResourceAuthDomainResult.{Constrained, NotConstrained, ResourceNotFound}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, PSQLStateExtensions}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext, groupByFirstInPair}
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.collection.concurrent.TrieMap
import scala.util.{Failure, Try}
import cats.effect.Temporal

class PostgresAccessPolicyDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)(implicit timer: Temporal[IO]) extends AccessPolicyDAO with DatabaseSupport with PostgresGroupDAO with EffectivePolicyMutationStatements with LazyLogging {

  /**
    * Cache of resource type name to pk mapping. It is important that this is used in serializable transactions
    * because reading the resource type name within a serializable transaction will lead to concurrency issues.
    * At best, all serializable transactions on the same resource type will collide and at worst,
    * ALL serializable transactions will collide (since the resource type table is small and will probably have
    * a seq scan instead of an index scan, meaning all serializable transactions would read all rows of the
    * resource type table and thus always collide with each other).
    */
  private val resourceTypePKsByName: scala.collection.concurrent.Map[ResourceTypeName, ResourceTypePK] = new TrieMap()

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
    val result = if (resourceTypes.nonEmpty) {
      serializableWriteTransaction("upsertResourceTypes", samRequestContext) { implicit session =>
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
          recreateEffectivePolicyRolesTableEntry(changedResourceTypeNames)
          logger.info(s"upsertResourceTypes: completed updates to resource types [$changedResourceTypeNames]")
        }
        changedResourceTypeNames
      }
    } else {
      IO.pure(Set.empty[ResourceTypeName])
    }
    result <* reloadResourceTypePKs(samRequestContext)
  }

  private def reloadResourceTypePKs(samRequestContext: SamRequestContext): IO[Unit] = {
    readOnlyTransaction("loadResourceTypePKsByName", samRequestContext) { implicit session =>
      val rt = ResourceTypeTable.syntax("rt")
      samsql"""select ${rt.result.name}, ${rt.result.id} from ${ResourceTypeTable as rt}""".map(rs =>
        rs.get[ResourceTypeName](rt.resultName.name) -> rs.get[ResourceTypePK](rt.resultName.id)).list().apply().toMap
    }.map { results =>
      resourceTypePKsByName
        .addAll(results)
        .filterInPlace((k, v) => results.exists(loaded => (k, v) == loaded))
    }
  }

  override def loadResourceTypes(resourceTypeNames: Set[ResourceTypeName], samRequestContext: SamRequestContext): IO[Set[ResourceType]] = {
    readOnlyTransaction("loadResourceTypes", samRequestContext) { implicit session =>
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

      samsql"""update ${ResourceRoleTable.table}
              set ${resourceRoleColumn.deprecated} = false
              where (${resourceRoleColumn.resourceTypeId}, ${resourceRoleColumn.role}) in ($roleValues)
              or ${resourceRoleColumn.resourceTypeId} not in (${resourceTypeNameToPKs.values})"""
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

  /**
    *
    * @param resource
    * @param samRequestContext
    * @return
    */
  override def createResource(resource: Resource, samRequestContext: SamRequestContext): IO[Resource] = {
    for {
      // Lookup some PKs in a read only transaction before the serializable transaction to
      // create the resource. Otherwise concurrently creating resources
      // may cause serialization failures because they would begin with these reads.
      authDomainGroupPKs <- listAuthDomainGroupPKs(resource, samRequestContext)

      policyIdToMemberGroupPKs <- resource.accessPolicies.toList.traverse { policy =>
        readOnlyTransaction("loadMemberGroupPKs", samRequestContext) { implicit session =>
          policy.id -> queryForGroupPKs(policy.members)
        }
      }.map(_.toMap)

      parentPK <- resource.parent.traverse { parent =>
        readOnlyTransaction("loadResourcePK", samRequestContext) { implicit session =>
          loadResourcePK(parent)
        }
      }

      resource <- serializableWriteTransaction("createResource", samRequestContext) { implicit session =>
        val resourcePK = insertResource(resource, parentPK)

        if (authDomainGroupPKs.nonEmpty) {
          insertAuthDomainsForResource(resourcePK, authDomainGroupPKs)
        }

        if (resource.parent.isDefined) {
          populateInheritedEffectivePolicies(resourcePK)
        }

        resource.accessPolicies.foreach { policy =>
          val groupPK = insertPolicyGroup(policy)
          val policyPK = insertPolicy(resourcePK, policy, groupPK)
          insertPolicyMembersRolesActions(policy, groupPK, policyPK, policyIdToMemberGroupPKs(policy.id))
        }

        resource
      }
    } yield {
      resource
    }
  }

  private def listAuthDomainGroupPKs(resource: Resource, samRequestContext: SamRequestContext): IO[Seq[GroupPK]] = {
    if (resource.authDomain.isEmpty) {
      IO.pure(Seq.empty[GroupPK])
    } else {
      readOnlyTransaction("listAuthDomainGroupPKs", samRequestContext) { implicit session =>
        val groupPKs = queryForGroupPKs(resource.authDomain.map(identity))
        if (groupPKs.size != resource.authDomain.size) {
          throw new WorkbenchException(s"did not find all auth domain groups in database for ${resource.fullyQualifiedId}, required ${resource.authDomain}, found $groupPKs")
        }
        groupPKs
      }
    }
  }

  private def insertResource(resource: Resource, parentPK: Option[ResourcePK])(implicit session: DBSession): ResourcePK = {
    val resourceTableColumn = ResourceTable.column
    // note that when setting the parent we are not checking for circular hierarchies but that should be ok
    // since this is a new resource and should not be a parent of another so no circles can be possible
    val insertResourceQuery =
      samsql"""insert into ${ResourceTable.table} (${resourceTableColumn.name}, ${resourceTableColumn.resourceTypeId}, ${resourceTableColumn.resourceParentId})
               values (${resource.resourceId}, ${resourceTypePKsByName(resource.resourceTypeName)}, $parentPK)"""

    Try {
      ResourcePK(insertResourceQuery.updateAndReturnGeneratedKey().apply())
    }.recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists")))
    }.get
  }

  private def insertAuthDomainsForResource(resourcePK: ResourcePK, authDomainGroupPKs: Seq[GroupPK])(implicit session: DBSession): Int = {
    val authDomainValues = authDomainGroupPKs.map { authDomainGroupPK =>
      samsqls"(${resourcePK}, ${authDomainGroupPK})"
    }

    val authDomainColumn = AuthDomainTable.column
    val insertAuthDomainQuery = samsql"insert into ${AuthDomainTable.table} (${authDomainColumn.resourceId}, ${authDomainColumn.groupId}) values ${authDomainValues}"

    insertAuthDomainQuery.update().apply()
  }

  /**
    * Queries the database for the PK of the resource and throws an error if it does not exist
    * @param resourceId
    * @return
    */
  private def loadResourcePK(resourceId: FullyQualifiedResourceId)(implicit session: DBSession): ResourcePK = {
    val pr = ResourceTable.syntax("pr")
    val loadResourcePKQuery =
      samsql"""select ${pr.result.id}
              | from ${ResourceTable as pr}
              | where ${pr.name} = ${resourceId.resourceId}
              | and ${pr.resourceTypeId} = ${resourceTypePKsByName(resourceId.resourceTypeName)}""".stripMargin

    loadResourcePKQuery.map(rs => ResourcePK(rs.long(pr.resultName.id))).single().apply().getOrElse(
      throw new WorkbenchException(s"resource $resourceId not found")
    )
  }

  override def deleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    serializableWriteTransaction("deleteResource", samRequestContext)({ implicit session =>
      deleteEffectivePolicies(resource, resourceTypePKsByName)

      val r = ResourceTable.syntax("r")
      samsql"""delete from ${ResourceTable as r}
              where ${r.name} = ${resource.resourceId}
              and ${r.resourceTypeId} = ${resourceTypePKsByName(resource.resourceTypeName)}""".update().apply()
    })
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LoadResourceAuthDomainResult] = {
    readOnlyTransaction("loadResourceAuthDomain", samRequestContext)({ implicit session =>
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

  override def listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity,
                                                                      samRequestContext: SamRequestContext): IO[Set[FullyQualifiedPolicyId]] = {
    readOnlyTransaction("listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup", samRequestContext)({ implicit session =>
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

      samsql"""
          select ${rt.result.name}, ${r.result.name}, ${p.result.name}
           from ${ResourceTable as r}
           join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
           join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
           join ${GroupTable as g} on ${p.groupId} = ${g.id}
           where ${r.id} in (${constrainedResourcesPKs})
           and ${g.synchronizedDate} is not null"""
          .map(rs =>
            FullyQualifiedPolicyId(
              FullyQualifiedResourceId(rs.get[ResourceTypeName](rt.resultName.name), rs.get[ResourceId](r.resultName.name)),
              rs.get[AccessPolicyName](p.resultName.name)))
        .list().apply().toSet
    })
  }

  override def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    val r = ResourceTable.syntax("r")
    val ad = AuthDomainTable.syntax("ad")
    val rt = ResourceTypeTable.syntax("rt")

    serializableWriteTransaction("removeAuthDomainFromResource", samRequestContext)({ implicit session =>
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
    serializableWriteTransaction("createPolicy", samRequestContext)({ implicit session =>
      val groupId = insertPolicyGroup(policy)
      val policyId = insertPolicy(policy, groupId)

      insertPolicyMembersRolesActions(policy, groupId, policyId, queryForGroupPKs(policy.members))

      policy
    }).recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"policy $policy already exists")))
    }
  }

  private def insertPolicyMembersRolesActions(policy: AccessPolicy, groupPK: GroupPK, policyPK: PolicyPK, memberGroupPKs: List[GroupPK])(implicit session: DBSession) = {
    insertGroupMemberPKs(groupPK, memberGroupPKs, collectUserIds(policy.members))
    insertPolicyRoles(FullyQualifiedResourceRole.fullyQualify(policy.roles, policy.id.resource.resourceTypeName), policyPK, false)
    insertPolicyRoles(policy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceRole.fullyQualify(permissions.roles, permissions.resourceType)), policyPK, true)
    insertPolicyActions(FullyQualifiedResourceAction.fullyQualify(policy.actions, policy.id.resource.resourceTypeName), policyPK, false)
    insertPolicyActions(policy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceAction.fullyQualify(permissions.actions, permissions.resourceType)), policyPK, true)
  }

  private def insertPolicyActions(actions: Set[FullyQualifiedResourceAction], policyId: PolicyPK, descendantsOnly: Boolean)(implicit session: DBSession): Int = {
    val ra = ResourceActionTable.syntax("ra")
    val paCol = PolicyActionTable.column
    if (actions.nonEmpty) {
      val actionsWithResourceTypeSql = actions.map {
        case FullyQualifiedResourceAction(action, resourceType) => samsqls"(${action}, ${resourceTypePKsByName(resourceType)})"
      }
      val insertQuery = samsqls"""insert into ${PolicyActionTable.table} (${paCol.resourcePolicyId}, ${paCol.resourceActionId}, ${paCol.descendantsOnly})
            select ${policyId}, ${ra.result.id}, ${descendantsOnly}
            from ${ResourceActionTable as ra}
            where (${ra.action}, ${ra.resourceTypeId}) in (${actionsWithResourceTypeSql})"""

      val inserted = samsql"$insertQuery".update().apply()

      val fullInsertCount = if (inserted != actions.size) {
        // in this case some actions that we want to insert did not exist in ResourceActionTable
        // add them now and rerun the insert ignoring conflicts
        // this case should happen rarely
        val actionsWithResourceTypePK = actions.map {
          case FullyQualifiedResourceAction(action, resourceTypeName) =>
            (action, resourceTypePKsByName.getOrElse(resourceTypeName,
              throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource type ${resourceTypeName} not found"))))
        }
        insertActions(actionsWithResourceTypePK)

        val moreInserted = samsql"$insertQuery on conflict do nothing".update().apply()

        inserted + moreInserted
      } else {
        inserted
      }
      insertEffectivePolicyActions(policyId)
      fullInsertCount
    } else {
      0
    }
  }

  private def insertPolicyRoles(roles: Set[FullyQualifiedResourceRole], policyId: PolicyPK, descendantsOnly: Boolean)(implicit session: DBSession): Int = {
    val rr = ResourceRoleTable.syntax("rr")
    val prCol = PolicyRoleTable.column
    if (roles.nonEmpty) {
      val rolesWithResourceTypeSql = roles.map {
        case FullyQualifiedResourceRole(role, resourceType) => samsqls"(${role}, ${resourceTypePKsByName(resourceType)})"
      }
      val insertedRolesCount = samsql"""insert into ${PolicyRoleTable.table} (${prCol.resourcePolicyId}, ${prCol.resourceRoleId}, ${prCol.descendantsOnly})
            select ${policyId}, ${rr.result.id}, ${descendantsOnly}
            from ${ResourceRoleTable as rr}
            where (${rr.role}, ${rr.resourceTypeId}) in (${rolesWithResourceTypeSql})
            and ${rr.deprecated} = false"""
        .update().apply()
      if (insertedRolesCount != roles.size) {
        throw new WorkbenchException("Some roles have been deprecated or were not found.")
      }
      insertEffectivePolicyRoles(policyId)
      insertedRolesCount
    } else {
      0
    }
  }

  private def insertPolicy(policy: AccessPolicy, groupPK: GroupPK)(implicit session: DBSession): PolicyPK = {
    insertPolicyInternal(samsqls"(${loadResourcePKSubQuery(policy.id.resource)})", policy, groupPK)
  }

  private def insertPolicy(resourcePK: ResourcePK, policy: AccessPolicy, groupPK: GroupPK)(implicit session: DBSession): PolicyPK = {
    insertPolicyInternal(samsqls"$resourcePK", policy, groupPK)
  }

  private def insertPolicyInternal(resourcePKFragment: SQLSyntax, policy: AccessPolicy, groupPK: GroupPK)(implicit session: DBSession): PolicyPK = {
    val pCol = PolicyTable.column
    val policyPK = PolicyPK(samsql"""insert into ${PolicyTable.table} (${pCol.resourceId}, ${pCol.groupId}, ${pCol.public}, ${pCol.name})
              values (${resourcePKFragment}, ${groupPK}, ${policy.public}, ${policy.id.accessPolicyName})""".updateAndReturnGeneratedKey().apply())

    insertEffectivePolicies(policy, policyPK)

    policyPK
  }

  private def insertPolicyGroup(policy: AccessPolicy)(implicit session: DBSession): GroupPK = {
    val gCol = GroupTable.column

    val policyGroupName = s"${policy.id.resource.resourceTypeName}_${policy.id.resource.resourceId}_${policy.id.accessPolicyName}"

    GroupPK(samsql"""insert into ${GroupTable.table} (${gCol.name}, ${gCol.email}, ${gCol.updatedDate})
               values (${policyGroupName}, ${policy.email}, ${Instant.now()})""".updateAndReturnGeneratedKey().apply())
  }

  private def directMemberUserEmailsQuery(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName]) = {
    val u = UserTable.syntax("u")
    val p = PolicyTable.syntax("p")
    val gm = GroupMemberTable.syntax("gm")

    samsql"""select ${p.result.name}, ${u.resultAll} from ${PolicyTable as p}
        join ${GroupMemberTable as gm} on ${gm.groupId} = ${p.groupId}
        join ${UserTable as u} on ${u.id} = ${gm.memberUserId}
        where ${p.resourceId} = (${loadResourcePKSubQuery(resource)})
        ${limitOnePolicyClause(limitOnePolicy, p)}"""
      .map(rs => (rs.get[AccessPolicyName](p.resultName.name), UserTable(u)(rs)))
  }

  private def directMemberGroupEmailsQuery(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName]) = {
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")
    val gm = GroupMemberTable.syntax("gm")
    val op = PolicyTable.syntax("op")

    samsql"""select ${p.result.name}, ${g.resultAll} from ${PolicyTable as p}
        join ${GroupMemberTable as gm} on ${gm.groupId} = ${p.groupId}
        join ${GroupTable as g} on ${g.id} = ${gm.memberGroupId}
        where ${p.resourceId} = (${loadResourcePKSubQuery(resource)})
        and not exists (select 1 from ${PolicyTable as op} where ${op.groupId} = ${g.id})
        ${limitOnePolicyClause(limitOnePolicy, p)}"""
      .map(rs => (rs.get[AccessPolicyName](p.resultName.name), GroupTable(g)(rs)))
  }

  private def limitOnePolicyClause(limitOnePolicy: Option[AccessPolicyName], p: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRecord], PolicyRecord]) = {
    limitOnePolicy match {
      case Some(policyName) => samsqls"and ${p.name} = ${policyName}"
      case None => samsqls""
    }
  }

  private def directMemberPolicyIdentifiersQuery(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName]) = {
    val mp = PolicyTable.syntax("mp") // member policy
    val mpr = ResourceTable.syntax("mpr") // member policy resource
    val mprt = ResourceTypeTable.syntax("mprt") // member policy resource type
    val mpg = GroupTable.syntax("mpg") // member policy group

    val p = PolicyTable.syntax("p")
    val gm = GroupMemberTable.syntax("gm")

    samsql"""select ${p.result.name}, ${mp.result.name}, ${mpg.result.email}, ${mpr.result.name}, ${mprt.result.name} from ${PolicyTable as p}
        join ${GroupMemberTable as gm} on ${gm.groupId} = ${p.groupId}
        join ${PolicyTable as mp} on ${mp.groupId} = ${gm.memberGroupId}
        join ${GroupTable as mpg} on ${mpg.id} = ${mp.groupId}
        join ${ResourceTable as mpr} on ${mpr.id} = ${mp.resourceId}
        join ${ResourceTypeTable as mprt} on ${mprt.id} = ${mpr.resourceTypeId}
        where ${p.resourceId} = (${loadResourcePKSubQuery(resource)})
        ${limitOnePolicyClause(limitOnePolicy, p)}"""
      .map(rs => (rs.get[AccessPolicyName](p.resultName.name), PolicyIdentifiers(rs.get[AccessPolicyName](mp.resultName.name), rs.get[WorkbenchEmail](mpg.resultName.email), rs.get[ResourceTypeName](mprt.resultName.name), rs.get[ResourceId](mpr.resultName.name))))
  }

  // Policies and their roles and actions are set to cascade delete when the associated group is deleted
  override def deletePolicy(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit] = {
    val p = PolicyTable.syntax("p")
    val g = GroupTable.syntax("g")

    serializableWriteTransaction("deletePolicy", samRequestContext)({ implicit session =>
      val policyGroupPKOpt = samsql"""delete from ${PolicyTable as p}
        where ${p.name} = ${policy.accessPolicyName}
        and ${p.resourceId} = (${loadResourcePKSubQuery(policy.resource)})
        returning ${p.groupId}""".map(rs => rs.long(1)).single().apply()

      policyGroupPKOpt.map { policyGroupPK =>
        samsql"""delete from ${GroupTable as g}
           where ${g.id} = ${policyGroupPK}""".update().apply()
      }
    })
  }

  override def deleteAllResourcePolicies(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    val p = PolicyTable.syntax("p")
    val g = GroupTable.syntax("g")

    val fromAndWhereFragment = samsqls"""from ${PolicyTable as p} where ${p.resourceId} = (${loadResourcePKSubQuery(resourceId)})"""
    val groupPKsToDeleteQuery: SQL[GroupPK, HasExtractor] = samsql"""select ${p.result.groupId} $fromAndWhereFragment""".map(_.get[GroupPK](p.resultName.groupId))

    for {
      writeResult <- serializableWriteTransaction("deletePolicy", samRequestContext)({ implicit session =>
        // first get all the group PKs associated to all the resource policies
        // then delete all the resource policies
        // then delete all the groups from the first query

        val groupPKsToDelete = groupPKsToDeleteQuery.list().apply()

        samsql"""delete $fromAndWhereFragment""".update().apply()

        if (groupPKsToDelete.nonEmpty) {
          samsql"""delete from ${GroupTable as g}
                 where ${g.id} in ($groupPKsToDelete)""".update().apply()
        }
      }).attempt
      _ <- handleResult(samRequestContext, writeResult, groupPKsToDeleteQuery)
    } yield ()
  }

  private def handleResult(samRequestContext: SamRequestContext, writeResult: Either[Throwable, AnyVal], groupPKsToDeleteQuery: SQL[GroupPK, HasExtractor]): IO[Unit] = {
    writeResult match {
      case Left(fkViolation: PSQLException) if fkViolation.getSQLState == PSQLStateExtensions.FOREIGN_KEY_VIOLATION =>
        handleForeignKeyViolation(samRequestContext, groupPKsToDeleteQuery)
      case Left(e) => IO.raiseError(e)
      case Right(_) => IO.unit
    }
  }

  private def handleForeignKeyViolation(samRequestContext: SamRequestContext, groupPKsToDeleteQuery: SQL[GroupPK, HasExtractor]): IO[Unit] = {
    for {
      problematicGroups <- getGroupsCausingForeignKeyViolation(samRequestContext, groupPKsToDeleteQuery)
      _ <- IO.raiseError[Unit](new WorkbenchExceptionWithErrorReport( // throws a 500 since that's the current behavior
        ErrorReport(StatusCodes.InternalServerError, s"Foreign Key Violation(s) while deleting group(s): ${problematicGroups}")))
    } yield ()
  }

  private def getGroupsCausingForeignKeyViolation(samRequestContext: SamRequestContext, groupPKsToDeleteQuery: SQL[GroupPK, HasExtractor]): IO[List[Map[String, String]]] = {
    val g = GroupTable.syntax("g")
    val pg = GroupTable.syntax("pg") // problematic group
    val gm = GroupMemberTable.syntax("gm")

    readOnlyTransaction("getGroupsCausingForeignKeyViolation", samRequestContext) { implicit session =>
      val groupPKsToDelete = groupPKsToDeleteQuery.list().apply()
      val problematicGroupsQuery =
        samsql"""select ${g.result.id}, ${g.result.name}, array_agg(${pg.name}) as ${pg.resultName.name}
                     from ${GroupTable as g}
                     join ${GroupMemberTable as gm} on ${g.id} = ${gm.memberGroupId}
                     join ${GroupTable as pg} on ${gm.groupId} = ${pg.id}
                     where ${g.id} in
                         (select distinct ${gm.result.memberGroupId}
                          from ${GroupMemberTable as gm}
                          where ${gm.memberGroupId} in ($groupPKsToDelete))
                     group by ${g.id}, ${g.name}"""
      problematicGroupsQuery.map(rs =>
          Map("groupId" -> rs.get[GroupPK](g.resultName.id).value.toString,
            "groupName" -> rs.get[String](g.resultName.name),
            "still used in group(s):" -> rs.get[String](pg.resultName.name)))
        .list().apply()
    }
  }

  override def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]] = {
    listPolicies(resourceAndPolicyName.resource, limitOnePolicy = Option(resourceAndPolicyName.accessPolicyName), samRequestContext).map(_.headOption)
  }

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[Unit] = {
    serializableWriteTransaction("overwritePolicyMembers", samRequestContext)({ implicit session =>
      overwritePolicyMembersInternal(id, memberList)
    })
  }

  //Steps: Delete every member from the underlying group and then add all of the new members. Do this in a *single*
  //transaction so if any bit fails, all of it fails and we don't end up in some incorrect intermediate state.
  private def overwritePolicyMembersInternal(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    val groupId = samsql"${workbenchGroupIdentityToGroupPK(id)}".map(rs => rs.get[GroupPK](1)).single().apply().getOrElse {
      throw new WorkbenchException(s"Group for policy [$id] not found")
    }
    removeAllGroupMembers(groupId)
    insertGroupMembers(groupId, memberList)
  }

  override def overwritePolicy(newPolicy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = {
    serializableWriteTransaction("overwritePolicy", samRequestContext)({ implicit session =>
      val policyPK = loadPolicyPK(newPolicy.id)
      overwritePolicyMembersInternal(newPolicy.id, newPolicy.members)
      overwritePolicyRolesInternal(policyPK, FullyQualifiedResourceRole.fullyQualify(newPolicy.roles, newPolicy.id.resource.resourceTypeName), newPolicy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceRole.fullyQualify(permissions.roles, permissions.resourceType)))
      overwritePolicyActionsInternal(policyPK, FullyQualifiedResourceAction.fullyQualify(newPolicy.actions, newPolicy.id.resource.resourceTypeName), newPolicy.descendantPermissions.flatMap(permissions => FullyQualifiedResourceAction.fullyQualify(permissions.actions, permissions.resourceType)))
      setPolicyIsPublicInternal(policyPK, newPolicy.public)

      newPolicy
    })
  }

  private def overwritePolicyRolesInternal(policyPK: PolicyPK, roles: Set[FullyQualifiedResourceRole], descendantRoles: Set[FullyQualifiedResourceRole])(implicit session: DBSession): Int = {
    val pr = PolicyRoleTable.syntax("pr")
    samsql"delete from ${PolicyRoleTable as pr} where ${pr.resourcePolicyId} = $policyPK".update().apply()
    deleteEffectivePolicyRoles(policyPK)

    insertPolicyRoles(roles, policyPK, false)
    insertPolicyRoles(descendantRoles, policyPK, true)
  }

  private def overwritePolicyActionsInternal(policyPK: PolicyPK, actions: Set[FullyQualifiedResourceAction], descendantActions: Set[FullyQualifiedResourceAction])(implicit session: DBSession): Int = {
    val pa = PolicyActionTable.syntax("pa")
    samsql"delete from ${PolicyActionTable as pa} where ${pa.resourcePolicyId} = $policyPK".update().apply()
    deleteEffectivePolicyActions(policyPK)

    insertPolicyActions(actions, policyPK, false)
    insertPolicyActions(descendantActions, policyPK, true)
  }

  private def loadPolicyPK(id: FullyQualifiedPolicyId)(implicit session: DBSession): PolicyPK = {
    val p = PolicyTable.syntax("p")
    samsql"select ${p.id} from ${PolicyTable as p} where ${p.name} = ${id.accessPolicyName} and ${p.resourceId} = (${loadResourcePKSubQuery(id.resource)})".map(rs => PolicyPK(rs.long(1))).single().apply().getOrElse {
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

    readOnlyTransaction("listPublicAccessPolicies-(returns ResourceIdAndPolicyName)", samRequestContext)({ implicit session =>
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

    readOnlyTransaction("listPublicAccessPolicies-(returns AccessPolicyWithoutMembers)", samRequestContext)({ implicit session =>
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
    listPoliciesWithMembers(resource, limitOnePolicy, samRequestContext) { (policyInfos, memberGroupsByPolicy, memberUsersByPolicy, memberPoliciesByPolicy) =>
      policyInfos.map { case (policyInfo, resultsByPolicy) =>
        val (policyRoles, policyActions, policyDescendantPermissions) = unmarshalPolicyPermissions(resultsByPolicy)

        val memberGroups = memberGroupsByPolicy(policyInfo.name).map(_.name).toSet[WorkbenchSubject]
        val memberUsers = memberUsersByPolicy(policyInfo.name).map(_.id).toSet[WorkbenchSubject]
        val memberPolicies = memberPoliciesByPolicy(policyInfo.name).map(p => FullyQualifiedPolicyId(FullyQualifiedResourceId(p.resourceTypeName, p.resourceId), p.policyName)).toSet[WorkbenchSubject]

        val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name)
        AccessPolicy(policyId, memberUsers ++ memberGroups ++ memberPolicies, policyInfo.email, policyRoles, policyActions, policyDescendantPermissions, policyInfo.public)
      }.to(LazyList)
    }
  }

  /**
    * Utility function that performs the required queries to list all policies for a resource or a load a single
    * policy including all actions, roles and members
    * @param resource resource to list policies for
    * @param limitOnePolicy name of policy if loading a single
    * @param samRequestContext
    * @param unmarshaller function that takes the results of the queries and returns a list of the desired object.
    *                     The first map keyed by the basic policy info and has values for roles and actions
    *                     The other maps are keyed by the policy name and have values for
    *                     member groups, users and policies in that order. These maps also have a default value
    *                     of List.empty so no need to worry about missing values.
    * @tparam T the return type
    * @return
    */
  private def listPoliciesWithMembers[T](resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName], samRequestContext: SamRequestContext)
                                        (unmarshaller: (
                                          Map[PolicyInfo, Iterable[(RoleResult, ActionResult)]],
                                          Map[AccessPolicyName, Iterable[GroupRecord]],
                                          Map[AccessPolicyName, Iterable[UserRecord]],
                                          Map[AccessPolicyName, Iterable[PolicyIdentifiers]]) => LazyList[T]): IO[LazyList[T]] = {
    readOnlyTransaction("listPoliciesWithMembers", samRequestContext)({ implicit session =>
      // query to get policies and all associated roles and actions
      val policyInfos = groupByFirstInPair(policyInfoQuery(resource, limitOnePolicy).list().apply())

      // queries to get all members, there are 3 kinds, groups, users and policies
      val memberGroupsByPolicy = groupByFirstInPair(directMemberGroupEmailsQuery(resource, limitOnePolicy).list().apply()).withDefault(_ => List.empty)
      val memberUsersByPolicy = groupByFirstInPair(directMemberUserEmailsQuery(resource, limitOnePolicy).list().apply()).withDefault(_ => List.empty)
      val memberPoliciesByPolicy = groupByFirstInPair(directMemberPolicyIdentifiersQuery(resource, limitOnePolicy).list().apply()).withDefault(_ => List.empty)

      // convert query results to desired output
      unmarshaller(policyInfos, memberGroupsByPolicy, memberUsersByPolicy, memberPoliciesByPolicy)
    })
  }

  private def policyInfoQuery(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName]) = {
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")
    val pr = PolicyRoleTable.syntax("pr")
    val rr = ResourceRoleTable.syntax("rr")
    val pa = PolicyActionTable.syntax("pa")
    val ra = ResourceActionTable.syntax("ra")
    val prrt = ResourceTypeTable.syntax("prrt") // policy role resource type
    val part = ResourceTypeTable.syntax("part") // policy action resource type

    val listPoliciesQuery =
      samsql"""select ${p.result.name}, ${g.result.email}, ${p.result.public}, ${prrt.result.name}, ${rr.result.role}, ${pr.result.descendantsOnly}, ${part.result.name}, ${ra.result.action}, ${pa.result.descendantsOnly}
          from ${GroupTable as g}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          left join ${PolicyRoleTable as pr} on ${p.id} = ${pr.resourcePolicyId}
          left join ${ResourceRoleTable as rr} on ${pr.resourceRoleId} = ${rr.id}
          left join ${ResourceTypeTable as prrt} on ${rr.resourceTypeId} = ${prrt.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          left join ${ResourceTypeTable as part} on ${ra.resourceTypeId} = ${part.id}
          where ${p.resourceId} = (${loadResourcePKSubQuery(resource)})
          ${limitOnePolicyClause(limitOnePolicy, p)}"""

    listPoliciesQuery.map(rs =>
      (
        PolicyInfo(
          rs.get[AccessPolicyName](p.resultName.name),
          resource.resourceId,
          resource.resourceTypeName,
          rs.get[WorkbenchEmail](g.resultName.email),
          rs.boolean(p.resultName.public)),
        (
          RoleResult(
            rs.stringOpt(prrt.resultName.name).map(ResourceTypeName(_)),
            rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)),
            rs.booleanOpt(pr.resultName.descendantsOnly)),
          ActionResult(
            rs.stringOpt(part.resultName.name).map(ResourceTypeName(_)),
            rs.stringOpt(ra.resultName.action).map(ResourceAction(_)),
            rs.booleanOpt(pa.resultName.descendantsOnly))
        )
      )
    )
  }

  override def loadPolicyMembership(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicyMembership]] = {
    listPoliciesMemberships(policyId.resource, Option(policyId.accessPolicyName), samRequestContext).map(_.headOption.map(_.membership))
  }

  override def listAccessPolicyMemberships(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithMembership]] = {
    listPoliciesMemberships(resource, None, samRequestContext)
  }

  // a variant of listPolicies but returns a structure that contains member emails and nested policy information instead of subject ids
  private def listPoliciesMemberships(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName] = None, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithMembership]] = {
    listPoliciesWithMembers(resource, limitOnePolicy, samRequestContext) { (policyInfos, memberGroupsByPolicy, memberUsersByPolicy, memberPoliciesByPolicy) =>
      policyInfos.map { case (policyInfo, resultsByPolicy) =>
        val (policyRoles, policyActions, policyDescendantPermissions) = unmarshalPolicyPermissions(resultsByPolicy)

        // policies have extra information, users and groups are just emails
        val memberGroups = memberGroupsByPolicy(policyInfo.name).map(_.email)
        val memberUsers = memberUsersByPolicy(policyInfo.name).map(_.email)
        val memberPolicies = memberPoliciesByPolicy(policyInfo.name).toSet

        AccessPolicyWithMembership(policyInfo.name, AccessPolicyMembership(memberPolicies.map(_.policyEmail) ++ memberUsers ++ memberGroups, policyActions, policyRoles, Option(policyDescendantPermissions), Option(memberPolicies)), policyInfo.email)
      }.to(LazyList)
    }
  }

  private def unmarshalPolicyPermissions(permissionsResults: Iterable[(RoleResult, ActionResult)]): (Set[ResourceRoleName], Set[ResourceAction], Set[AccessPolicyDescendantPermissions]) = {
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
    if(resourceId.nonEmpty) {
      readOnlyTransaction("listResourcesWithAuthdomains", samRequestContext)({ implicit session =>
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
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val f = GroupMemberFlatTable.syntax("f")
    val p = PolicyTable.syntax("p")

    readOnlyTransaction("listAccessPolicies", samRequestContext)({ implicit session =>
      samsql"""select ${r.result.name}, ${p.result.name}
         from ${PolicyTable as p}
         join ${GroupMemberFlatTable as f} on ${f.groupId} = ${p.groupId}
         join ${ResourceTable as r} on ${r.id} = ${p.resourceId}
         join ${ResourceTypeTable as rt} on ${rt.id} = ${r.resourceTypeId}
         where ${rt.name} = ${resourceTypeName}
         and ${f.memberUserId} = ${userId}""".map(rs => ResourceIdAndPolicyName(rs.get[ResourceId](r.resultName.name), rs.get[AccessPolicyName](p.resultName.name))).list().apply().toSet
    })
  }

  override def listUserResourcesWithRolesAndActions(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Iterable[ResourceIdWithRolesAndActions]] = {
    readOnlyTransaction("listUserResourcesWithRolesAndActions", samRequestContext)({ implicit session =>
      class ListUserResourcesQuery extends UserResourcesQuery(resourceTypeName, None, userId) {
        val policyRole = EffectivePolicyRoleTable.syntax("policyRole")
        val resourceRole = ResourceRoleTable.syntax("resourceRole")
        val policyActionJoin = EffectivePolicyActionTable.syntax("policyActionJoin")
        val policyAction = ResourceActionTable.syntax("policyAction")

        override val selectColumns: scalikejdbc.SQLSyntax = {
          samsqls"""${resource.result.name}, ${resourceRole.result.role}, ${policyAction.result.action}, ${policy.result.public}, ${policy.resourceId} != ${resource.id} as inherited"""
        }

        override val additionalJoins: scalikejdbc.SQLSyntax = {
          samsqls"""
          left join ${EffectivePolicyRoleTable as policyRole} on ${effPol.id} = ${policyRole.effectiveResourcePolicyId}
          left join ${ResourceRoleTable as resourceRole} on ${policyRole.resourceRoleId} = ${resourceRole.id}
          left join ${EffectivePolicyActionTable as policyActionJoin} on ${effPol.id} = ${policyActionJoin.effectiveResourcePolicyId}
          left join ${ResourceActionTable as policyAction} on ${policyActionJoin.resourceActionId} = ${policyAction.id}
          """
        }

        override val additionalConditions: Option[scalikejdbc.SQLSyntax] = {
          Option(samsqls"""${resourceRole.role} is not null or ${policyAction.action} is not null""")
        }
      }

      val listUserResourcesQuery = new ListUserResourcesQuery()
      val queryResults = listUserResourcesQuery.query.map { rs =>
        val rolesAndActions = RolesAndActions(rs.stringOpt(listUserResourcesQuery.resourceRole.resultName.role).toSet.map(ResourceRoleName(_)), rs.stringOpt(listUserResourcesQuery.policyAction.resultName.action).toSet.map(ResourceAction(_)))
        val public = rs.boolean(listUserResourcesQuery.policy.resultName.public)
        val inherited = rs.boolean("inherited")
        ResourceIdWithRolesAndActions(
          ResourceId(rs.string(listUserResourcesQuery.resource.resultName.name)),
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
    readOnlyTransaction("listAccessPoliciesForUser", samRequestContext)({ implicit session =>
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")
      val f = GroupMemberFlatTable.syntax("f")
      val g = GroupTable.syntax("g")
      val p: scalikejdbc.QuerySQLSyntaxProvider[scalikejdbc.SQLSyntaxSupport[PolicyRecord], PolicyRecord] = PolicyTable.syntax("p")

      val pr = PolicyRoleTable.syntax("pr")
      val rr = ResourceRoleTable.syntax("rr")
      val pa = PolicyActionTable.syntax("pa")
      val ra = ResourceActionTable.syntax("ra")

      val listPoliciesQuery =
        samsql"""select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${rr.result.role}, ${ra.result.action}
          from ${GroupTable as g}
          join ${GroupMemberFlatTable as f} on ${f.groupId} = ${g.id}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
          join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
          left join ${PolicyRoleTable as pr} on ${p.id} = ${pr.resourcePolicyId}
          left join ${ResourceRoleTable as rr} on ${pr.resourceRoleId} = ${rr.id}
          left join ${PolicyActionTable as pa} on ${p.id} = ${pa.resourcePolicyId}
          left join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
          where ${r.name} = ${resource.resourceId}
          and ${rt.name} = ${resource.resourceTypeName}"""

      val results = listPoliciesQuery.map(rs => (PolicyInfo(rs.get[AccessPolicyName](p.resultName.name), rs.get[ResourceId](r.resultName.name), rs.get[ResourceTypeName](rt.resultName.name), rs.get[WorkbenchEmail](g.resultName.email), rs.boolean(p.resultName.public)),
        (rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)), rs.stringOpt(ra.resultName.action).map(ResourceAction(_))))).list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, roleActionResults) = resultsByPolicy.unzip

        val (roles, actions) = roleActionResults.unzip

        AccessPolicyWithoutMembers(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name), policyInfo.email, roles.flatten.toSet, actions.flatten.toSet, policyInfo.public)
      }.toSet
    })

  }
  override def listUserResourceActions(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceAction]] = {
    readOnlyTransaction("listUserResourceActions", samRequestContext)({ implicit session =>
      class RoleActionQuery extends UserResourcesQuery(resourceId.resourceTypeName, Option(resourceId.resourceId), user) {
        val policyRole = EffectivePolicyRoleTable.syntax("policyRole")
        val resourceRole = ResourceRoleTable.syntax("resourceRole")
        val roleActionJoin = RoleActionTable.syntax("roleActionJoin")
        val roleAction = ResourceActionTable.syntax("roleAction")

        override val selectColumns: scalikejdbc.SQLSyntax = samsqls"${roleAction.action} as action"

        override val additionalJoins: scalikejdbc.SQLSyntax =
          samsqls"""
          join ${EffectivePolicyRoleTable as policyRole} on ${effPol.id} = ${policyRole.effectiveResourcePolicyId}
          join ${ResourceRoleTable as resourceRole} on ${policyRole.resourceRoleId} = ${resourceRole.id}
          join ${RoleActionTable as roleActionJoin} on ${resourceRole.id} = ${roleActionJoin.resourceRoleId}
          join ${ResourceActionTable as roleAction} on ${roleActionJoin.resourceActionId} = ${roleAction.id}
          """

        override val additionalConditions: Option[scalikejdbc.SQLSyntax] = None
      }

      class PolicyActionQuery extends UserResourcesQuery(resourceId.resourceTypeName, Option(resourceId.resourceId), user) {
        val policyActionJoin = EffectivePolicyActionTable.syntax("policyActionJoin")
        val policyAction = ResourceActionTable.syntax("policyAction")

        override val selectColumns: scalikejdbc.SQLSyntax = samsqls"${policyAction.action} as action"

        override val additionalJoins: scalikejdbc.SQLSyntax =
          samsqls"""
          join ${EffectivePolicyActionTable as policyActionJoin} on ${effPol.id} = ${policyActionJoin.effectiveResourcePolicyId}
          join ${ResourceActionTable as policyAction} on ${policyActionJoin.resourceActionId} = ${policyAction.id}
          """

        override val additionalConditions: Option[scalikejdbc.SQLSyntax] = None
      }

      samsql"${new RoleActionQuery().querySyntax} union ${new PolicyActionQuery().querySyntax}"
        .map(rs => ResourceAction(rs.string("action"))).list().apply().toSet
    })
  }

  override def listUserResourceRoles(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceRoleName]] = {
    readOnlyTransaction("listUserResourceRoles", samRequestContext)({ implicit session =>
      class RoleQuery extends UserResourcesQuery(resourceId.resourceTypeName, Option(resourceId.resourceId), user) {
        val policyRole = EffectivePolicyRoleTable.syntax("policyRole")
        val resourceRole = ResourceRoleTable.syntax("resourceRole")
        val roleAction = ResourceActionTable.syntax("roleAction")

        override val selectColumns: scalikejdbc.SQLSyntax = samsqls"${resourceRole.result.role}"

        override val additionalJoins: scalikejdbc.SQLSyntax =
          samsqls"""
          join ${EffectivePolicyRoleTable as policyRole} on ${effPol.id} = ${policyRole.effectiveResourcePolicyId}
          join ${ResourceRoleTable as resourceRole} on ${policyRole.resourceRoleId} = ${resourceRole.id}
          """

        override val additionalConditions: Option[scalikejdbc.SQLSyntax] = None
      }
      val roleQuery = new RoleQuery()
      roleQuery.query.map(rs => ResourceRoleName(rs.string(roleQuery.resourceRole.resultName.role))).list().apply().toSet
    })
  }

  override def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Set[SamUser]] = {
    val f = GroupMemberFlatTable.syntax("f")
    val u = UserTable.syntax("u")
    val p = PolicyTable.syntax("p")
    val r = ResourceTable.syntax("r")

    readOnlyTransaction("listFlattenedPolicyMembers", samRequestContext)({ implicit session =>
      val query = samsql"""select distinct ${u.resultAll}
        from ${GroupMemberFlatTable as f}
        join ${UserTable as u} on ${u.id} = ${f.memberUserId}
        join ${PolicyTable as p} on ${p.groupId} = ${f.groupId}
        join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
        where ${r.resourceTypeId} = ${resourceTypePKsByName(policyId.resource.resourceTypeName)}
        and ${r.name} = ${policyId.resource.resourceId}
        and ${p.name} = ${policyId.accessPolicyName}"""

      query.map(UserTable(u)).list().apply().toSet.map(UserTable.unmarshalUserRecord)
    })
  }

  override def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean, samRequestContext: SamRequestContext): IO[Boolean] = {
    serializableWriteTransaction("setPolicyIsPublic", samRequestContext)({ implicit session =>
      val policyPK = loadPolicyPK(policyId)
      setPolicyIsPublicInternal(policyPK, isPublic) > 0
    })
  }

  override def getResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]] = {
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val pr = ResourceTable.syntax("pr")
    val prt = ResourceTypeTable.syntax("prt")

    readOnlyTransaction("getResourceParent", samRequestContext)({ implicit session =>
      val query = samsql"""select ${pr.result.name}, ${prt.result.name}
              from ${ResourceTable as r}
              join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              join ${ResourceTable as pr} on ${pr.id} = ${r.resourceParentId}
              join ${ResourceTypeTable as prt} on ${pr.resourceTypeId} = ${prt.id}
              where ${r.name} = ${resource.resourceId}
              and ${rt.name} = ${resource.resourceTypeName}"""

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

    serializableWriteTransaction("setResourceParent", samRequestContext)({ implicit session =>
      val parentResourcePK = loadResourcePK(parentResource)
      val childResourcePK = loadResourcePK(childResource)

      deleteDescendantInheritedEffectivePolicies(childResourcePK)

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

      populateInheritedEffectivePolicies(childResourcePK)
    })
  }

  override def deleteResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean] = {
    val r = ResourceTable.syntax("r")
    val resourceTableColumn = ResourceTable.column

    serializableWriteTransaction("deleteResourceParent", samRequestContext)({ implicit session =>
      val resourcePK = loadResourcePK(resource)
      deleteDescendantInheritedEffectivePolicies(resourcePK)
      val query =
        samsql"""update ${ResourceTable as r}
          set ${resourceTableColumn.resourceParentId} = null
          where ${r.id} = ${resourcePK}"""

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

    readOnlyTransaction("getResourceChildren", samRequestContext)({ implicit session =>
      query.map(rs =>
        FullyQualifiedResourceId(
          rs.get[ResourceTypeName](crt.resultName.name),
          rs.get[ResourceId](cr.resultName.name)))
        .list().apply().toSet
    })
  }

  private def recreateEffectivePolicyRolesTableEntry(resourceTypeNames: Set[ResourceTypeName])(implicit session: DBSession) : Int = {
    val resource = ResourceTable.syntax("resource")
    val policyResource = ResourceTable.syntax("policyResource")
    val resourceRole = ResourceRoleTable.syntax("resourceRole")
    val flattenedRole = FlattenedRoleMaterializedView.syntax("flattenedRole")
    val policyResourceType = ResourceTypeTable.syntax("policyResourceType")
    val roleResourceType = ResourceTypeTable.syntax("roleResourceType")
    val policy = PolicyTable.syntax("policy")
    val policyRole = PolicyRoleTable.syntax("policyRole")
    val effectivePolicyRole = EffectivePolicyRoleTable.syntax("effectivePolicyRole")
    val effectiveResourcePolicy = EffectiveResourcePolicyTable.syntax("effectiveResourcePolicy")

    samsql"""delete from ${EffectivePolicyRoleTable as effectivePolicyRole}
             using ${EffectiveResourcePolicyTable as effectiveResourcePolicy},
             ${PolicyTable as policy},
             ${ResourceTable as policyResource},
             ${ResourceTypeTable as policyResourceType},
             ${ResourceRoleTable as resourceRole},
             ${ResourceTypeTable as roleResourceType}
             where ${effectivePolicyRole.effectiveResourcePolicyId} = ${effectiveResourcePolicy.id}
             and ${effectiveResourcePolicy.sourcePolicyId} = ${policy.id}
             and ${policy.resourceId} = ${policyResource.id}
             and ${policyResource.resourceTypeId} = ${policyResourceType.id}
             and ${effectivePolicyRole.resourceRoleId} = ${resourceRole.id}
             and ${resourceRole.resourceTypeId} = ${roleResourceType.id}
             and (
               ${policyResourceType.name} IN (${resourceTypeNames})
               or ${roleResourceType.name} IN (${resourceTypeNames})
             )
          """.update().apply()

    samsql"""insert into ${EffectivePolicyRoleTable.table}(${EffectivePolicyRoleTable.column.effectiveResourcePolicyId}, ${EffectivePolicyRoleTable.column.resourceRoleId})
             select ${effectiveResourcePolicy.id}, ${resourceRole.id} from
             ${EffectiveResourcePolicyTable as effectiveResourcePolicy}
             join ${PolicyRoleTable as policyRole} on ${effectiveResourcePolicy.sourcePolicyId} = ${policyRole.resourcePolicyId}
             join ${ResourceTable as resource} on ${effectiveResourcePolicy.resourceId} = ${resource.id}
             join ${FlattenedRoleMaterializedView as flattenedRole} on ${policyRole.resourceRoleId} = ${flattenedRole.baseRoleId}
             join ${ResourceRoleTable as resourceRole} on ${flattenedRole.nestedRoleId} = ${resourceRole.id} and ${resource.resourceTypeId} = ${resourceRole.resourceTypeId}
             join ${PolicyTable as policy} on ${effectiveResourcePolicy.sourcePolicyId} = ${policy.id}
             join ${ResourceTable as policyResource} on ${policy.resourceId} = ${policyResource.id}
             join ${ResourceTypeTable as policyResourceType} on ${policyResource.resourceTypeId} = ${policyResourceType.id}
             join ${ResourceTypeTable as roleResourceType} on ${resourceRole.resourceTypeId} = ${roleResourceType.id}
             where (((${policy.resourceId} != ${effectiveResourcePolicy.resourceId} and (${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))
              or not ((${policy.resourceId} != ${effectiveResourcePolicy.resourceId}) or ${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))
              and (${policyResourceType.name} IN (${resourceTypeNames})
               or ${roleResourceType.name} IN (${resourceTypeNames})))
              on conflict do nothing
          """.update().apply()
  }

    private def setPolicyIsPublicInternal(policyPK: PolicyPK, isPublic: Boolean)(implicit session: DBSession): Int = {
    val p = PolicyTable.syntax("p")
    val policyTableColumn = PolicyTable.column

    samsql"""update ${PolicyTable as p}
              set ${policyTableColumn.public} = ${isPublic}
              where ${p.id} = ${policyPK}
              and ${p.public} != ${isPublic}""".update().apply()
  }

  /**
    * This class encapsulates the common parts of queries for user resources such as list all roles on an action
    * or list all resources of a type with actions and roles.
    *
    * This is a class instead of a function because it has lots of inputs and lots of outputs and describing that
    * in terms of parameters and return values is messy. This seemed the cleanest approach.
    *
    * @param resourceTypeName type name to query for
    * @param resourceId resource id if applicable
    * @param user
    */
  abstract class UserResourcesQuery(resourceTypeName: ResourceTypeName, resourceId: Option[ResourceId], user: WorkbenchUserId) {
    val resource = ResourceTable.syntax("resource")
    val resourceType = ResourceTypeTable.syntax("resourceType")
    val flatGroupMember = GroupMemberFlatTable.syntax("flatGroupMember")
    val policy = PolicyTable.syntax("policy")

    val effPol = EffectiveResourcePolicyTable.syntax("effPol")

    val resourceIdFragment = resourceId.map(id => samsqls"and ${resource.name} = ${id}").getOrElse(samsqls"")

    def querySyntax = {
      // query has 2 parts, first is for policies user is a member of, second is for public policies
      val renderedAdditionalConditions = additionalConditions.map(c => samsqls"and ($c)").getOrElse(samsqls"")
      samsqls"""
          select $selectColumns
          from ${ResourceTable as resource}
          join ${ResourceTypeTable as resourceType} on ${resource.resourceTypeId} = ${resourceType.id}
          join ${EffectiveResourcePolicyTable as effPol} on ${resource.id} = ${effPol.resourceId}
          join ${PolicyTable as policy} on ${effPol.sourcePolicyId} = ${policy.id}
          join ${GroupMemberFlatTable as flatGroupMember} on ${flatGroupMember.groupId} = ${policy.groupId}
          $additionalJoins
          where ${resourceType.name} = ${resourceTypeName}
          ${resourceIdFragment}
          and ${flatGroupMember.memberUserId} = ${user}
          $renderedAdditionalConditions
          union
          select $selectColumns
          from ${ResourceTable as resource}
          join ${ResourceTypeTable as resourceType} on ${resource.resourceTypeId} = ${resourceType.id}
          join ${EffectiveResourcePolicyTable as effPol} on ${resource.id} = ${effPol.resourceId}
          join ${PolicyTable as policy} on ${effPol.sourcePolicyId} = ${policy.id}
          $additionalJoins
          where ${resourceType.name} = ${resourceTypeName}
          ${resourceIdFragment}
          and ${policy.public}
          $renderedAdditionalConditions
          """
    }

    def query = samsql"$querySyntax"

    def selectColumns: SQLSyntax

    def additionalJoins: SQLSyntax

    def additionalConditions: Option[SQLSyntax]
  }

  private def loadResourcePKSubQuery(resource: FullyQualifiedResourceId): SQLSyntax = {
    val r = ResourceTable.syntax("r")
    samsqls"select ${r.id} from ${ResourceTable as r} where ${r.name} = ${resource.resourceId} and ${r.resourceTypeId} = ${resourceTypePKsByName(resource.resourceTypeName)}"
  }
}

private final case class PolicyInfo(name: AccessPolicyName, resourceId: ResourceId, resourceTypeName: ResourceTypeName, email: WorkbenchEmail, public: Boolean)
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
