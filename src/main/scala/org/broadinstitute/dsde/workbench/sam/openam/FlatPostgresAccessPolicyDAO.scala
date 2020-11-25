package org.broadinstitute.dsde.workbench.sam.openam

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.tables.{EffectivePolicyTable, FlatGroupMemberTable, GroupRecord, GroupTable, PolicyActionTable, PolicyRoleTable, PolicyTable, ResourceActionTable, ResourceRoleTable, ResourceTable, ResourceTypeTable}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, AccessPolicyName, FullyQualifiedPolicyId, FullyQualifiedResourceId, ResourceAction, ResourceId, ResourceIdAndPolicyName, ResourceRoleName, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import scalikejdbc.SQLSyntax
import scalikejdbc._

import scala.concurrent.ExecutionContext
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders

/**
  * Replace the "classic" hierarchical/recursive model with a flattened DB structure optimized for quick reads.
  *
  * For the design, see Appendix A in https://docs.google.com/document/d/1pXAhic_GxM-G9qBFTk0JiTF9YY4nUJfdUEWvbkd_rNw
  */
class FlatPostgresAccessPolicyDAO (override val dbRef: DbReference, override val ecForDatabaseIO: ExecutionContext)
                                  (implicit override val cs: ContextShift[IO]) extends PostgresAccessPolicyDAO(dbRef, ecForDatabaseIO) {

  override def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]] = {
    listPolicies(resourceAndPolicyName.resource, limitOnePolicy = Option(resourceAndPolicyName.accessPolicyName), samRequestContext).map(_.headOption)
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Stream[AccessPolicy]] = {
    listPolicies(resource, samRequestContext = samRequestContext)
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceIdAndPolicyName]] = {
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val f = FlatGroupMemberTable.syntax("f")
    val p = PolicyTable.syntax("p")

    runInTransaction("listAccessPolicies", samRequestContext)({ implicit session =>
      import SamTypeBinders._

      samsql"""select ${r.result.name}, ${p.result.name}
         from ${PolicyTable as p}
         join ${FlatGroupMemberTable as f} on ${f.groupId} = ${p.groupId}
         join ${ResourceTable as r} on ${r.id} = ${p.resourceId}
         join ${ResourceTypeTable as rt} on ${rt.id} = ${r.resourceTypeId}
         where ${rt.name} = ${resourceTypeName}""".map(rs => ResourceIdAndPolicyName(rs.get[ResourceId](r.resultName.name), rs.get[AccessPolicyName](p.resultName.name))).list().apply().toSet
    })
  }

  override def userPoliciesCommonTableExpressions(resourceTypeName: ResourceTypeName, resourceId: Option[ResourceId], user: WorkbenchUserId): UserPoliciesCommonTableExpression = {
    val resource = ResourceTable.syntax("resource")
    val resourceType = ResourceTypeTable.syntax("resourceType")
    val flatGroupMember = FlatGroupMemberTable.syntax("flatGroupMember")
    val policy = PolicyTable.syntax("policy")

    val userResourcePolicyTable = UserResourcePolicyTable("user_resource_policy")
    val userResourcePolicy = userResourcePolicyTable.syntax("userResourcePolicy")
    val urpColumn = userResourcePolicyTable.column

    val effPol = EffectivePolicyTable.syntax("effPol")

    val resourceIdFragment = resourceId.map(id => samsqls"and ${resource.name} = ${id}").getOrElse(samsqls"")

    val queryFragment =
      samsqls"""${userResourcePolicyTable.table}(${urpColumn.policyId}, ${urpColumn.baseResourceTypeId}, ${urpColumn.baseResourceName}, ${urpColumn.inherited}, ${urpColumn.public}) as (
          select ${effPol.id}, ${resourceType.id}, ${resource.name}, ${policy.resourceId} != ${resource.id}, ${effPol.public}
          from ${ResourceTable as resource}
          join ${ResourceTypeTable as resourceType} on ${resource.resourceTypeId} = ${resourceType.id}
          join ${EffectivePolicyTable as effPol} on ${resource.id} = ${effPol.resourceId}
          join ${PolicyTable as policy} on ${effPol.sourcePolicyId} = ${policy.id}
          where ${resourceType.name} = ${resourceTypeName}
          ${resourceIdFragment}
          and (${effPol.public} OR ${effPol.groupId} in
          (select ${flatGroupMember.groupId} from ${FlatGroupMemberTable as flatGroupMember} where ${flatGroupMember.memberUserId} = ${user})))"""

    UserPoliciesCommonTableExpression(userResourcePolicyTable, userResourcePolicy, queryFragment)
  }

  // Copied from PostgresAccessPolicyDAO.listPolicies
  // Assumption: we only want *direct membership* here, based on the original query
  private def listPolicies(resource: FullyQualifiedResourceId, limitOnePolicy: Option[AccessPolicyName] = None, samRequestContext: SamRequestContext): IO[Stream[AccessPolicy]] = {
    val g = GroupTable.syntax("g")
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    val f = FlatGroupMemberTable.syntax("f")
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
      samsql"""select ${p.result.name}, ${r.result.name}, ${rt.result.name}, ${g.result.email}, ${p.result.public}, ${f.result.memberUserId}, ${sg.result.name}, ${sp.result.name}, ${sr.result.name}, ${srt.result.name}, ${prrt.result.name}, ${rr.result.role}, ${pr.result.descendantsOnly}, ${part.result.name}, ${ra.result.action}, ${pa.result.descendantsOnly}
          from ${GroupTable as g}
          join ${PolicyTable as p} on ${g.id} = ${p.groupId}
          join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
          join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
          left join ${FlatGroupMemberTable as f} on ${g.id} = ${f.groupId} and ${directMembershipClause(g)}
          left join ${GroupTable as sg} on ${f.memberGroupId} = ${sg.id}
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
      val results = listPoliciesQuery.map(rs => (PolicyInfo(
        rs.get[AccessPolicyName](p.resultName.name),
        rs.get[ResourceId](r.resultName.name),
        rs.get[ResourceTypeName](rt.resultName.name),
        rs.get[WorkbenchEmail](g.resultName.email),
        rs.boolean(p.resultName.public)),
        MemberResult(rs.stringOpt(f.resultName.memberUserId).map(WorkbenchUserId),
          rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
          rs.stringOpt(sp.resultName.name).map(AccessPolicyName(_)),
          rs.stringOpt(sr.resultName.name).map(ResourceId(_)),
          rs.stringOpt(srt.resultName.name).map(ResourceTypeName(_))),
        (RoleResult(rs.stringOpt(prrt.resultName.name).map(ResourceTypeName(_)),
          rs.stringOpt(rr.resultName.role).map(ResourceRoleName(_)),
          rs.booleanOpt(pr.resultName.descendantsOnly)),
          ActionResult(rs.stringOpt(part.resultName.name).map(ResourceTypeName(_)),
            rs.stringOpt(ra.resultName.action).map(ResourceAction(_)),
            rs.booleanOpt(pa.resultName.descendantsOnly)))))
        .list().apply().groupBy(_._1)

      results.map { case (policyInfo, resultsByPolicy) =>
        val (_, memberResults, permissionsResults) = resultsByPolicy.unzip3

        val policyMembers = unmarshalPolicyMembers(memberResults)

        val (policyRoles, policyActions, policyDescendantPermissions) = unmarshalPolicyPermissions(permissionsResults)

        AccessPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(policyInfo.resourceTypeName, policyInfo.resourceId), policyInfo.name),
          policyMembers, policyInfo.email, policyRoles, policyActions, policyDescendantPermissions, policyInfo.public)
      }.toStream
    })
  }

  // selection clause for direct membership:
  // choose when the final `groupMembershipPath` array element is equal to groupId
  private def directMembershipClause(g: QuerySQLSyntaxProvider[SQLSyntaxSupport[GroupRecord], GroupRecord]): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = ${g.id}"
  }

}
