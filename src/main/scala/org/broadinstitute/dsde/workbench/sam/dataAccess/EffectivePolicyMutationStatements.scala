package org.broadinstitute.dsde.workbench.sam.dataAccess

import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, FullyQualifiedResourceId, ResourceTypeName}
import scalikejdbc.{DBSession, _}

/**
  * Resources can specify access policies that affect their descendants. Checking these inherited policies
  * at query time is expensive as it requires a recursive query. Effective policies are an optimization
  * that shifts this burden from read time to write time by calculating and storing all policies that are
  * effective for all resources so that at query time the lookup is straight forward, less stress on the db and fast.
  *
  * An effective policy applies to one and only one resource, never to descendants. It contains roles and actions
  * that are applicable only to the resource; it is not required to check if roles are descendant only or apply
  * to other resource types.
  *
  * It is crucial that all resource and policy updates are in serializable transactions to avoid race conditions when
  * concurrent modifications are made affecting the same resource structure.
  */
trait EffectivePolicyMutationStatements {
  /**
    * Delete all effective policies on the specified resource.
    * @param resource
    * @param session
    * @return
    */
  protected def deleteEffectivePolicies(resource: FullyQualifiedResourceId, resourceTypePKsByName: collection.Map[ResourceTypeName, ResourceTypePK])(implicit session: DBSession): Int = {
    val r = ResourceTable.syntax("r")
    val ep = EffectivePolicyTable.syntax("ep")

    samsql"""delete from ${EffectivePolicyTable as ep}
               using ${ResourceTable as r}
               where ${r.name} = ${resource.resourceId}
               and ${r.resourceTypeId} = ${resourceTypePKsByName(resource.resourceTypeName)}
               and ${r.id} = ${ep.resourceId}
              """.update().apply()
  }

  /**
    * Delete all inherited effective policies on the specified resource and descendants.
    * @param childResourcePK
    * @param session
    * @return
    */
  protected def deleteDescendantInheritedEffectivePolicies(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")

    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val ep = EffectivePolicyTable.syntax("ep")
    val p = PolicyTable.syntax("p")

    // find all polices in ancestor resources
    // delete any effective policy from descendant resources where source policy id is from an ancestor policy
    samsql"""with recursive
        ${ancestorsRecursiveQuery(childResourcePK, ancestorResourceTable)},
        ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

        delete from ${EffectivePolicyTable as ep}
        using ${ancestorResourceTable as ancestorResource},
        ${descendantResourceTable as descendantResource},
        ${PolicyTable as p}
        where ${ep.resourceId} = ${descendantResource.resourceId}
        and ${p.resourceId} = ${ancestorResource.resourceId}
        and ${p.id} = ${ep.sourcePolicyId}""".update().apply()
  }

  /**
    * Calculate and store all effective policies for resource for given resource and its descendants.
    * @param childResourcePK
    * @param session
    * @return
    */
  protected def populateInheritedEffectivePolicies(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    insertEffectivePoliciesForChildAndDescendants(childResourcePK) +
      insertEffectivePolicyActionsForChildAndDescendants(childResourcePK) +
      insertEffectivePolicyRolesForChildAndDescendants(childResourcePK)
  }

  private def insertEffectivePolicyRolesForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val erCol = EffectivePolicyRoleTable.column
    val pr = PolicyRoleTable.syntax("pr")
    val fr = FlattenedRoleMaterializedView.syntax("fr")
    val rr = ResourceRoleTable.syntax("rr")
    val ep = EffectivePolicyTable.syntax("ep")
    val sourcePolicy = PolicyTable.syntax("source_policy")
    val r = ResourceTable.syntax("r")

    samsql"""with recursive
              ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

              insert into ${EffectivePolicyRoleTable.table} (${erCol.resourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${descendantResourceTable as descendantResource}
              join ${EffectivePolicyTable as ep} on ${descendantResource.resourceId} = ${ep.resourceId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${ep.sourcePolicyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as sourcePolicy} on ${sourcePolicy.id} = ${ep.sourcePolicyId}
              where ${r.resourceTypeId} = ${rr.resourceTypeId}
              and ${roleAppliesToResource(pr, fr, ep, sourcePolicy)}
              on conflict do nothing""".update().apply()
  }

  private def insertEffectivePolicyActionsForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val eaCol = EffectivePolicyActionTable.column
    val pa = PolicyActionTable.syntax("pa")
    val r = ResourceTable.syntax("r")
    val ra = ResourceActionTable.syntax("ra")
    val ep = EffectivePolicyTable.syntax("ep")
    val sourcePolicy = PolicyTable.syntax("source_policy")

    samsql"""with recursive
              ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

              insert into ${EffectivePolicyActionTable.table} (${eaCol.resourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${descendantResourceTable as descendantResource}
              join ${EffectivePolicyTable as ep} on ${descendantResource.resourceId} = ${ep.resourceId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${ep.sourcePolicyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as sourcePolicy} on ${sourcePolicy.id} = ${ep.sourcePolicyId}
              where ${pa.descendantsOnly} = (${sourcePolicy.resourceId} != ${ep.resourceId})
              and ${r.resourceTypeId} = ${ra.resourceTypeId}
              on conflict do nothing""".update().apply()
  }

  private def insertEffectivePoliciesForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")

    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val p = PolicyTable.syntax("p")
    val epCol = EffectivePolicyTable.column

    samsql"""with recursive
        ${ancestorsRecursiveQuery(childResourcePK, ancestorResourceTable)},
        ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

        insert into ${EffectivePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId}, ${epCol.groupId}, ${epCol.public})
        select ${descendantResource.resourceId}, ${p.id}, ${p.groupId}, ${p.public}
        from ${descendantResourceTable as descendantResource},
        ${ancestorResourceTable as ancestorResource}
        join ${PolicyTable as p} on ${ancestorResource.resourceId} = ${p.resourceId}""".update().apply()
  }

  private def ancestorsRecursiveQuery(childResourcePK: ResourcePK, ancestorResourceTable: AncestorResourceTable) = {
    val resource = ResourceTable.syntax("resource")
    val parentResource = ResourceTable.syntax("parentResource")
    val arColumn = ancestorResourceTable.column
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")
    samsqls"""
        ${ancestorResourceTable.table}(${arColumn.resourceId}) as (
          select ${resource.resourceParentId}
          from ${ResourceTable as resource}
          where ${resource.id} = ${childResourcePK} and ${resource.resourceParentId} is not null
          union
          select ${parentResource.resourceParentId}
          from ${ResourceTable as parentResource}
          join ${ancestorResourceTable as ancestorResource} on ${ancestorResource.resourceId} = ${parentResource.id}
          where ${parentResource.resourceParentId} is not null)"""
  }

  private def descendantsRecursiveQuery(childResourcePK: ResourcePK, descendantResourceTable: AncestorResourceTable) = {
    val resource = ResourceTable.syntax("resource")
    val drColumn = descendantResourceTable.column
    val descendantResource = descendantResourceTable.syntax("descendantResource")
    samsqls"""
        ${descendantResourceTable.table}(${drColumn.resourceId}) as (
            select ${childResourcePK}
            union
            select ${resource.id}
            from ${ResourceTable as resource}
            join ${descendantResourceTable as descendantResource} on ${descendantResource.resourceId} = ${resource.resourceParentId})"""
  }

  /**
    * Insert effective policy roles from the specified policy for all descendants.
    * @param policyId
    * @param session
    * @return
    */
  protected def insertEffectivePolicyRoles(policyId: PolicyPK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")
    val erCol = EffectivePolicyRoleTable.column

    val ep = EffectivePolicyTable.syntax("ep")
    val pr = PolicyRoleTable.syntax("pr")
    val r = ResourceTable.syntax("r")
    val fr = FlattenedRoleMaterializedView.syntax("fr")
    val rr = ResourceRoleTable.syntax("rr")

    val policy = PolicyTable.syntax("policy")

    samsql"""with recursive ${descendantResourcesCTE(policyId, ancestorResourceTable)}

              insert into ${EffectivePolicyRoleTable.table} (${erCol.resourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${ancestorResourceTable as ar}
              join ${EffectivePolicyTable as ep} on ${ar.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${policyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id},
              ${PolicyTable as policy}
              where ${policy.id} = ${policyId}
              and ${r.resourceTypeId} = ${rr.resourceTypeId}
              and ${roleAppliesToResource(pr, fr, ep, policy)}
              on conflict do nothing""".update().apply()
  }

  /**
    * Insert effective policy actions from the specified policy for all descendants.
    * @param policyId
    * @param session
    * @return
    */
  protected def insertEffectivePolicyActions(policyId: PolicyPK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")
    val eaCol = EffectivePolicyActionTable.column

    val ep = EffectivePolicyTable.syntax("ep")
    val pa = PolicyActionTable.syntax("pa")
    val r = ResourceTable.syntax("r")
    val ra = ResourceActionTable.syntax("ra")

    val policy = PolicyTable.syntax("policy")

    samsql"""with recursive ${descendantResourcesCTE(policyId, ancestorResourceTable)}

              insert into ${EffectivePolicyActionTable.table} (${eaCol.resourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${ancestorResourceTable as ar}
              join ${EffectivePolicyTable as ep} on ${ar.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${policyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id},
              ${PolicyTable as policy}
              where ${policy.id} = ${policyId}
              and ${pa.descendantsOnly} = (${policy.resourceId} != ${ep.resourceId})
              and ${r.resourceTypeId} = ${ra.resourceTypeId}
              on conflict do nothing""".update().apply()
  }

  /**
    * Insert effective policy records for descendants of resource associated to policyPK.
    * This does not include roles or actions.
    * @param policy
    * @param groupId
    * @param policyPK
    * @param session
    * @return
    */
  protected def insertEffectivePolicies(policy: AccessPolicy, groupId: GroupPK, policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val epCol = EffectivePolicyTable.column
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")

    samsql"""with recursive ${descendantResourcesCTE(policyPK, ancestorResourceTable)}
            insert into ${EffectivePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId}, ${epCol.groupId}, ${epCol.public})
            select ${ar.resourceId}, ${policyPK}, ${groupId}, ${policy.public}
            from ${ancestorResourceTable as ar}
            """.update().apply()
  }

  private def descendantResourcesCTE(policyPK: PolicyPK, ancestorResourceTable: AncestorResourceTable) = {
    val ar = ancestorResourceTable.syntax("ar")
    val arColumn = ancestorResourceTable.column

    val child = ResourceTable.syntax("child")
    val p = PolicyTable.syntax("p")

    samsqls"""${ancestorResourceTable.table}(${arColumn.resourceId}) as (
            select ${p.resourceId}
            from ${PolicyTable as p}
            where ${p.id} = ${policyPK}
            union
            select ${child.id}
            from ${ResourceTable as child}
            join ${ancestorResourceTable as ar} on ${ar.resourceId} = ${child.resourceParentId})"""
  }

  /**
    * Delete all roles on effecitve policies with source policy of policyPK.
    * @param policyPK
    * @param session
    * @return
    */
  protected def deleteEffectivePolicyRoles(policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val ep = EffectivePolicyTable.syntax("ep")
    val epr = EffectivePolicyRoleTable.syntax(("epr"))
    samsql"""delete from ${EffectivePolicyRoleTable as epr}
              using ${EffectivePolicyTable as ep}
              where ${ep.sourcePolicyId} = $policyPK
              and ${epr.resourcePolicyId} = ${ep.id}""".update().apply()
  }

  /**
    * Delete all actions on effecitve policies with source policy of policyPK.
    * @param policyPK
    * @param session
    * @return
    */
  protected def deleteEffectivePolicyActions(policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val ep = EffectivePolicyTable.syntax("ep")
    val epa = EffectivePolicyActionTable.syntax(("epa"))
    samsql"""delete from ${EffectivePolicyActionTable as epa}
              using ${EffectivePolicyTable as ep}
              where ${ep.sourcePolicyId} = $policyPK
              and ${epa.resourcePolicyId} = ${ep.id}""".update().apply()
  }

  protected def setEffectivePoliciesPublic(policyPK: PolicyPK, isPublic: Boolean)(implicit session: DBSession): Int = {
    val ep = EffectivePolicyTable.syntax("ep")
    val effectivePolicyTableColumn = EffectivePolicyTable.column
    samsql"""update ${EffectivePolicyTable as ep}
              set ${effectivePolicyTableColumn.public} = ${isPublic}
              where ${ep.sourcePolicyId} = ${policyPK}""".update().apply()
  }

  /**
    *
    * Determining whether a role should or should not apply to a resource is a bit more complicated than it initially
    * appears. This logic is shared across queries that search a resource's hierarchy for all of the relevant roles and actions
    * that a user has on said resource. The following truth table shows the desired behavior of this SQL fragment where
    * result indicates whether a given role does or does not apply to the resource.
    * inherited is determined by sourcePolicy.resourceId != effectivePolicy.resourceId
    *
    *         inherited             |  policyRole.descendantsOnly  |  flattenedRole.descendantsOnly  |  result
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
  private def roleAppliesToResource(policyRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRoleRecord], PolicyRoleRecord],
                                    flattenedRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlattenedRoleRecord], FlattenedRoleRecord],
                                    effectivePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[EffectivePolicyRecord], EffectivePolicyRecord],
                                    sourcePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRecord], PolicyRecord]) = {
    samsqls"""(((${sourcePolicy.resourceId} != ${effectivePolicy.resourceId}) and (${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))
             or not ((${sourcePolicy.resourceId} != ${effectivePolicy.resourceId}) or ${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))"""
  }

}
