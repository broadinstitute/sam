package org.broadinstitute.dsde.workbench.sam.dataAccess

import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, FullyQualifiedResourceId, ResourceTypeName}
import scalikejdbc.{DBSession, _}

/** Resources can specify access policies that affect their descendants. Checking these inherited policies at query time is expensive as it requires a recursive
  * query. Effective policies are an optimization that shifts this burden from read time to write time by calculating and storing all policies that are
  * effective for all resources so that at query time the lookup is straight forward, less stress on the db and fast.
  *
  * An effective policy applies to one and only one resource, never to descendants. It contains roles and actions that are applicable only to the resource; it
  * is not required to check if roles are descendant only or apply to other resource types.
  *
  * It is crucial that all resource and policy updates are in serializable transactions to avoid race conditions when concurrent modifications are made
  * affecting the same resource structure.
  */
trait EffectivePolicyMutationStatements {
  protected val resourceTypePKsByName: scala.collection.concurrent.Map[ResourceTypeName, ResourceTypePK]

  /** Delete all effective policies on the specified resource.
    * @param resource
    * @param session
    * @return
    */
  protected def deleteEffectivePolicies(resource: FullyQualifiedResourceId, resourceTypePKsByName: collection.Map[ResourceTypeName, ResourceTypePK])(implicit
      session: DBSession
  ): Int = {
    val r = ResourceTable.syntax("r")
    val ep = EffectiveResourcePolicyTable.syntax("ep")

    samsql"""delete from ${EffectiveResourcePolicyTable as ep}
               using ${ResourceTable as r}
               where ${r.name} = ${resource.resourceId}
               and ${r.resourceTypeId} = ${resourceTypePKsByName(resource.resourceTypeName)}
               and ${r.id} = ${ep.resourceId}
              """.update().apply()
  }

  /** Delete all effective policies on the resource specified by childResourcePK and all of its descendants that are inherited from ancestors of
    * childResourcePK.
    *
    * @param childResourcePK
    * @param session
    * @return
    */
  protected def deleteDescendantInheritedEffectivePolicies(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")

    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val p = PolicyTable.syntax("p")

    // find all polices in ancestor resources
    // delete any effective policy from descendant resources where source policy id is from an ancestor policy
    samsql"""with recursive
        ${ancestorsRecursiveQuery(childResourcePK, ancestorResourceTable)},
        ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

        delete from ${EffectiveResourcePolicyTable as ep}
        using ${ancestorResourceTable as ancestorResource},
        ${descendantResourceTable as descendantResource},
        ${PolicyTable as p}
        where ${ep.resourceId} = ${descendantResource.resourceId}
        and ${p.resourceId} = ${ancestorResource.resourceId}
        and ${p.id} = ${ep.sourcePolicyId}""".update().apply()
  }

  /** Calculate and store all effective policies for resource for given resource and its descendants.
    * @param childResourcePK
    * @param session
    * @return
    */
  protected def populateInheritedEffectivePolicies(childResourcePK: ResourcePK)(implicit session: DBSession): Int =
    insertEffectivePoliciesForChildAndDescendants(childResourcePK) +
      insertEffectivePolicyActionsForChildAndDescendants(childResourcePK) +
      insertEffectivePolicyRolesForChildAndDescendants(childResourcePK)

  /** For this resource and all of its descendants, find all of the effective policies and unroll any roles that those policies grant. Check that they are
    * applicable to the resource in question and insert them if so.
    *
    * @param childResourcePK
    * @param session
    * @return
    */
  private def insertEffectivePolicyRolesForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    // Note that there is no special logic for resource type admin because resource type admin may not have parents
    // and must exist before any resources of that type are created. This function is called only when a resource
    // is created or when a resource's parent is changed.
    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val erCol = EffectivePolicyRoleTable.column
    val pr = PolicyRoleTable.syntax("pr")
    val fr = FlattenedRoleMaterializedView.syntax("fr")
    val rr = ResourceRoleTable.syntax("rr")
    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val sourcePolicy = PolicyTable.syntax("source_policy")
    val r = ResourceTable.syntax("r")

    samsql"""with recursive
              ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

              insert into ${EffectivePolicyRoleTable.table} (${erCol.effectiveResourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${descendantResourceTable as descendantResource}
              join ${EffectiveResourcePolicyTable as ep} on ${descendantResource.resourceId} = ${ep.resourceId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${ep.sourcePolicyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as sourcePolicy} on ${sourcePolicy.id} = ${ep.sourcePolicyId}
              where ${r.resourceTypeId} = ${rr.resourceTypeId}
              and ${roleAppliesToResource(pr, fr, ep, sourcePolicy)}
              on conflict do nothing""".update().apply()
  }

  /** For this resource and all of its descendants, find all of the policies that could possibly apply to them and grab all the actions on those policies and
    * then insert them into the EPA table if the resource type of the action matches the resource type of the descendant.
    *
    * @param childResourcePK
    * @param session
    * @return
    */
  private def insertEffectivePolicyActionsForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    // Note that there is no special logic for resource type admin because resource type admin may not have parents
    // and must exist before any resources of that type are created. This function is called only when a resource
    // is created or when a resource's parent is changed.
    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val eaCol = EffectivePolicyActionTable.column
    val pa = PolicyActionTable.syntax("pa")
    val r = ResourceTable.syntax("r")
    val ra = ResourceActionTable.syntax("ra")
    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val sourcePolicy = PolicyTable.syntax("source_policy")

    samsql"""with recursive
              ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

              insert into ${EffectivePolicyActionTable.table} (${eaCol.effectiveResourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${descendantResourceTable as descendantResource}
              join ${EffectiveResourcePolicyTable as ep} on ${descendantResource.resourceId} = ${ep.resourceId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${ep.sourcePolicyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as sourcePolicy} on ${sourcePolicy.id} = ${ep.sourcePolicyId}
              where ${pa.descendantsOnly} = (${sourcePolicy.resourceId} != ${ep.resourceId})
              and ${r.resourceTypeId} = ${ra.resourceTypeId}
              on conflict do nothing""".update().apply()
  }

  /** For this resource and all of its descendants, insert a record in the EP table for every policy on this resource or any of its ancestors.
    *
    * @param childResourcePK
    * @param session
    * @return
    */
  private def insertEffectivePoliciesForChildAndDescendants(childResourcePK: ResourcePK)(implicit session: DBSession): Int = {
    // Note that there is no special logic for resource type admin because resource type admin may not have parents
    // and must exist before any resources of that type are created. This function is called only when a resource
    // is created or when a resource's parent is changed.
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ancestorResource = ancestorResourceTable.syntax("ancestorResource")

    val descendantResourceTable = AncestorResourceTable("descendant_resource")
    val descendantResource = descendantResourceTable.syntax("descendantResource")

    val p = PolicyTable.syntax("p")
    val epCol = EffectiveResourcePolicyTable.column

    samsql"""with recursive
        ${ancestorsRecursiveQuery(childResourcePK, ancestorResourceTable)},
        ${descendantsRecursiveQuery(childResourcePK, descendantResourceTable)}

        insert into ${EffectiveResourcePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId})
        select ${descendantResource.resourceId}, ${p.id}
        from ${descendantResourceTable as descendantResource},
        ${ancestorResourceTable as ancestorResource}
        join ${PolicyTable as p} on ${ancestorResource.resourceId} = ${p.resourceId}""".update().apply()
  }

  /** Returns a recursive query to be used in a with clause (CTE) whose result includes all the ancestors of the given childResourcePK.
    *
    * @param resourcePK
    * @param descendantResourceTable
    * @return
    */
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

  /** Returns a recursive query to be used in a with clause (CTE) whose result includes the given resourcePK and PKs of all of its descendants.
    *
    * @param resourcePK
    * @param descendantResourceTable
    * @return
    */
  private def descendantsRecursiveQuery(resourcePK: ResourcePK, descendantResourceTable: AncestorResourceTable) = {
    val resource = ResourceTable.syntax("resource")
    val drColumn = descendantResourceTable.column
    val descendantResource = descendantResourceTable.syntax("descendantResource")
    samsqls"""
        ${descendantResourceTable.table}(${drColumn.resourceId}) as (
            select ${resourcePK}
            union
            select ${resource.id}
            from ${ResourceTable as resource}
            join ${descendantResourceTable as descendantResource} on ${descendantResource.resourceId} = ${resource.resourceParentId})"""
  }

  /** Insert effective policy roles from the specified policy for all descendants.
    * @param policyId
    * @param session
    * @return
    */
  protected def insertEffectivePolicyRoles(policyId: PolicyPK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")
    val erCol = EffectivePolicyRoleTable.column

    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val pr = PolicyRoleTable.syntax("pr")
    val r = ResourceTable.syntax("r")
    val fr = FlattenedRoleMaterializedView.syntax("fr")
    val rr = ResourceRoleTable.syntax("rr")

    val policy = PolicyTable.syntax("policy")

    samsql"""with recursive ${descendantsRecursiveQuery(policyId, ancestorResourceTable)}

              insert into ${EffectivePolicyRoleTable.table} (${erCol.effectiveResourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${ancestorResourceTable as ar}
              join ${EffectiveResourcePolicyTable as ep} on ${ar.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${policyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as policy} on ${policy.id} = ${ep.sourcePolicyId}
              where ${r.resourceTypeId} = ${rr.resourceTypeId}
              and ${roleAppliesToResource(pr, fr, ep, policy)}
              on conflict do nothing""".update().apply()
  }

  protected def insertResourceTypeAdminEffectivePolicyRoles(policyId: PolicyPK, resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val erCol = EffectivePolicyRoleTable.column

    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val pr = PolicyRoleTable.syntax("pr")
    val r = ResourceTable.syntax("r")
    val fr = FlattenedRoleMaterializedView.syntax("fr")
    val rr = ResourceRoleTable.syntax("rr")

    val policy = PolicyTable.syntax("policy")

    samsql"""insert into ${EffectivePolicyRoleTable.table} (${erCol.effectiveResourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${PolicyTable as policy}
              join ${EffectiveResourcePolicyTable as ep} on ${policy.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${policyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              where ${r.resourceTypeId} = ${rr.resourceTypeId}
              and ${roleAppliesToResource(pr, fr, ep, policy)}
              on conflict do nothing""".update().apply()

    samsql"""insert into ${EffectivePolicyRoleTable.table} (${erCol.effectiveResourcePolicyId}, ${erCol.resourceRoleId})
              select ${ep.id}, ${rr.id}
              from ${ResourceTable as r}
              join ${EffectiveResourcePolicyTable as ep} on ${r.id} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyRoleTable as pr} on ${pr.resourcePolicyId} = ${policyId}
              join ${FlattenedRoleMaterializedView as fr} on ${pr.resourceRoleId} = ${fr.baseRoleId}
              join ${ResourceRoleTable as rr} on ${fr.nestedRoleId} = ${rr.id}
              join ${PolicyTable as policy} on ${policy.id} = ${ep.sourcePolicyId}
              where ${r.resourceTypeId} = ${resourceTypePK}
              and ${roleAppliesToResource(pr, fr, ep, policy)}
              on conflict do nothing""".update().apply()
  }

  /** Insert effective policy actions from the specified policy for all descendants.
    * @param policyId
    * @param session
    * @return
    */
  protected def insertEffectivePolicyActions(policyId: PolicyPK)(implicit session: DBSession): Int = {
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")
    val eaCol = EffectivePolicyActionTable.column

    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val pa = PolicyActionTable.syntax("pa")
    val r = ResourceTable.syntax("r")
    val ra = ResourceActionTable.syntax("ra")

    val policy = PolicyTable.syntax("policy")

    samsql"""with recursive ${descendantsRecursiveQuery(policyId, ancestorResourceTable)}

              insert into ${EffectivePolicyActionTable.table} (${eaCol.effectiveResourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${ancestorResourceTable as ar}
              join ${EffectiveResourcePolicyTable as ep} on ${ar.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${policyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              join ${PolicyTable as policy} ON ${policy.id} = ${ep.sourcePolicyId}
              where ${pa.descendantsOnly} = (${policy.resourceId} != ${ep.resourceId})
              and ${r.resourceTypeId} = ${ra.resourceTypeId}
              on conflict do nothing""".update().apply()
  }

  protected def insertResourceTypeAdminEffectivePolicyActions(policyId: PolicyPK, resourceTypePK: ResourceTypePK)(implicit session: DBSession): Int = {
    val eaCol = EffectivePolicyActionTable.column

    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val pa = PolicyActionTable.syntax("pa")
    val r = ResourceTable.syntax("r")
    val ra = ResourceActionTable.syntax("ra")
    val policy = PolicyTable.syntax("policy")

    samsql"""insert into ${EffectivePolicyActionTable.table} (${eaCol.effectiveResourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${PolicyTable as policy}
              join ${EffectiveResourcePolicyTable as ep} on ${policy.resourceId} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${policyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id}
              join ${ResourceTable as r} on ${ep.resourceId} = ${r.id}
              where not ${pa.descendantsOnly}
              and ${policy.id} = ${policyId}
              and ${r.resourceTypeId} = ${ra.resourceTypeId}
              on conflict do nothing""".update().apply()

    samsql"""insert into ${EffectivePolicyActionTable.table} (${eaCol.effectiveResourcePolicyId}, ${eaCol.resourceActionId})
              select ${ep.id}, ${ra.id}
              from ${ResourceTable as r}
              join ${EffectiveResourcePolicyTable as ep} on ${r.id} = ${ep.resourceId} and ${ep.sourcePolicyId} = ${policyId}
              join ${PolicyActionTable as pa} on ${pa.resourcePolicyId} = ${policyId}
              join ${ResourceActionTable as ra} on ${pa.resourceActionId} = ${ra.id} and ${ra.resourceTypeId} = ${resourceTypePK}
              where ${pa.descendantsOnly}
              and ${r.resourceTypeId} = ${resourceTypePK}
              on conflict do nothing""".update().apply()
  }

  /** Insert effective policy records for descendants of resource associated to policyPK. This does not include roles or actions.
    * @param policy
    * @param policyPK
    * @param session
    * @return
    */
  protected def insertEffectivePolicies(policy: AccessPolicy, policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val epCol = EffectiveResourcePolicyTable.column
    val ancestorResourceTable = AncestorResourceTable("ancestor_resource")
    val ar = ancestorResourceTable.syntax("ar")

    samsql"""with recursive ${descendantsRecursiveQuery(policyPK, ancestorResourceTable)}
            insert into ${EffectiveResourcePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId})
            select ${ar.resourceId}, ${policyPK}
            from ${ancestorResourceTable as ar}
            """.update().apply()
  }

  /** Insert effective policy records for resource type admin policies. A resource type admin resource is a logical parent of all resources of that type. This does not include roles or actions.
    */
  protected def insertResourceTypeAdminEffectivePolicies(policy: AccessPolicy, policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val epCol = EffectiveResourcePolicyTable.column
    val r = ResourceTable.syntax("r")

    // insert for resource type admin resource itself
    samsql"""insert into ${EffectiveResourcePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId})
             values (${resourceTypePKsByName(policy.id.resource.resourceTypeName)}, $policyPK)""".update().apply()

    // insert for all resources of the type
    samsql"""insert into ${EffectiveResourcePolicyTable.table} (${epCol.resourceId}, ${epCol.sourcePolicyId})
          select ${r.id}, ${policyPK}
          from ${ResourceTable as r}
          where ${r.resourceTypeId} = ${resourceTypePKsByName(policy.id.resource.resourceTypeName)}
          """.update().apply()
  }

  /** Returns a recursive query to be used in a with clause (CTE) whose result includes thePK of the resource of the given policyPK and PKs of all of its
    * descendants.
    *
    * @param resourcePK
    * @param descendantResourceTable
    * @return
    */
  private def descendantsRecursiveQuery(policyPK: PolicyPK, ancestorResourceTable: AncestorResourceTable) = {
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

  /** Delete all roles on effecitve policies with source policy of policyPK.
    * @param policyPK
    * @param session
    * @return
    */
  protected def deleteEffectivePolicyRoles(policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val epr = EffectivePolicyRoleTable.syntax("epr")
    samsql"""delete from ${EffectivePolicyRoleTable as epr}
              using ${EffectiveResourcePolicyTable as ep}
              where ${ep.sourcePolicyId} = $policyPK
              and ${epr.effectiveResourcePolicyId} = ${ep.id}""".update().apply()
  }

  /** Delete all actions on effecitve policies with source policy of policyPK.
    * @param policyPK
    * @param session
    * @return
    */
  protected def deleteEffectivePolicyActions(policyPK: PolicyPK)(implicit session: DBSession): Int = {
    val ep = EffectiveResourcePolicyTable.syntax("ep")
    val epa = EffectivePolicyActionTable.syntax("epa")
    samsql"""delete from ${EffectivePolicyActionTable as epa}
              using ${EffectiveResourcePolicyTable as ep}
              where ${ep.sourcePolicyId} = $policyPK
              and ${epa.effectiveResourcePolicyId} = ${ep.id}""".update().apply()
  }

  /** Determining whether a role should or should not apply to a resource is a bit more complicated than it initially appears. This logic is shared across
    * queries that search a resource's hierarchy for all of the relevant roles and actions that a user has on said resource. The following truth table shows the
    * desired behavior of this SQL fragment where result indicates whether a given role does or does not apply to the resource. inherited is determined by
    * sourcePolicy.resourceId != effectivePolicy.resourceId
    *
    * inherited | policyRole.descendantsOnly | flattenedRole.descendantsOnly | result T | T | T | T T | T | F | T T | F | T | T T | F | F | F F | T | T | F F |
    * T | F | F F | F | T | F F | F | F | T
    */
  private def roleAppliesToResource(
      policyRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRoleRecord], PolicyRoleRecord],
      flattenedRole: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlattenedRoleRecord], FlattenedRoleRecord],
      effectivePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[EffectiveResourcePolicyRecord], EffectiveResourcePolicyRecord],
      sourcePolicy: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRecord], PolicyRecord]
  ) =
    samsqls"""(((${sourcePolicy.resourceId} != ${effectivePolicy.resourceId}) and (${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))
             or not ((${sourcePolicy.resourceId} != ${effectivePolicy.resourceId}) or ${policyRole.descendantsOnly} or ${flattenedRole.descendantsOnly}))"""

}
