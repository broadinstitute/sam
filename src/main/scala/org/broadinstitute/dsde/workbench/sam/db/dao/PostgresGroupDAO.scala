package org.broadinstitute.dsde.workbench.sam.db.dao
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import scalikejdbc.{DBSession, SQLSyntax}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId

import scala.concurrent.ExecutionContext

class PostgresGroupDAO(protected val dbRef: DbReference,
                       protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends DatabaseSupport {
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val memberUsers: List[SQLSyntax] = members.collect {
        case userId: WorkbenchUserId => samsqls"(${groupId}, ${userId}, ${None})"
      }.toList

      val memberGroupPKQueries = members.collect {
        case id: WorkbenchGroupIdentity => samsqls"(${workbenchGroupIdentityToGroupPK(id)})"
      }

      import SamTypeBinders._
      // TODO: is there a way to do this without needing N subqueries?
      val memberGroupPKs: List[GroupPK] = if (memberGroupPKQueries.nonEmpty) {
        val g = GroupTable.syntax("g")
        samsql"select ${g.result.id} from ${GroupTable as g} where ${g.id} in (${memberGroupPKQueries})"
          .map(rs => rs.get[GroupPK](g.resultName.id)).list().apply()
      } else {
        List.empty
      }

      val memberGroups: List[SQLSyntax] = memberGroupPKs.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK})"
      }

      if (memberGroups.size != memberGroupPKQueries.size) {
        throw new WorkbenchException(s"Some member groups not found.")
      } else {
        val gm = GroupMemberTable.column
        samsql"insert into ${GroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}) values ${memberUsers ++ memberGroups}"
          .update().apply()
      }
    }
  }

  def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    groupId match {
      case group: WorkbenchGroupName => GroupTable.groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
    }
  }

  private def groupPKQueryForPolicy(policyId: FullyQualifiedPolicyId,
                                    resourceTypeTableAlias: String = "rt",
                                    resourceTableAlias: String = "r",
                                    policyTableAlias: String = "p",
                                    groupTableAlias: String = "g"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    val r = ResourceTable.syntax(resourceTableAlias)
    val p = PolicyTable.syntax(policyTableAlias)
    val g = GroupTable.syntax(groupTableAlias)
    samsqls"""select ${p.groupId}
              from ${ResourceTypeTable as rt}
              join ${ResourceTable as r} on ${rt.id} = ${r.resourceTypeId}
              join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
              join ${GroupTable as g} on ${g.id} = ${p.groupId}
              where ${rt.name} = ${policyId.resource.resourceTypeName}
              and ${r.name} = ${policyId.resource.resourceId}
              and ${p.name} = ${policyId.accessPolicyName}"""
  }
}
