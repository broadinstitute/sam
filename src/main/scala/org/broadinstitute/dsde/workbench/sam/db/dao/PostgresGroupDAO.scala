package org.broadinstitute.dsde.workbench.sam.db.dao
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchGroupIdentity, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.tables.{GroupMemberTable, GroupPK, GroupTable}
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import scalikejdbc.{DBSession, SQLSyntax}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders

import scala.concurrent.ExecutionContext

class PostgresGroupDAO(protected val dbRef: DbReference,
               protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends DatabaseSupport {
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val memberUsers: List[SQLSyntax] = members.collect {
        case userId: WorkbenchUserId => samsqls"(${groupId}, ${Option(userId)}, ${None})"
      }.toList

      val memberGroupPKQueries = members.collect {
        case id: WorkbenchGroupIdentity => samsqls"(${workbenchGroupIdentityToGroupPK(id)})"
      }

      import SamTypeBinders._
      val memberGroupPKs: List[GroupPK] = if (memberGroupPKQueries.nonEmpty) {
        val g = GroupTable.syntax("g")
        samsql"select ${g.result.id} from ${GroupTable as g} where ${g.id} in (${memberGroupPKQueries})"
          .map(rs => rs.get[GroupPK](g.resultName.id)).list().apply()
      } else {
        List.empty
      }

      val memberGroups: List[SQLSyntax] = memberGroupPKs.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${Option(groupPK)})"
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
}
