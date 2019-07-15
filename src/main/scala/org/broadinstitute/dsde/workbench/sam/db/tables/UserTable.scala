package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import scalikejdbc._

final case class UserRecord(id: WorkbenchUserId,
                            email: WorkbenchEmail,
                            googleSubjectId: Option[GoogleSubjectId])

object UserTable extends SQLSyntaxSupport[UserRecord] {
  override def tableName: String = "SAM_USER"

  import UserTableBinders._
  def apply(e: ResultName[UserRecord])(rs: WrappedResultSet): UserRecord = UserRecord(
    rs.get(e.id),
    rs.get(e.email),
    rs.get(e.googleSubjectId)
  )
}

object UserTableBinders {
  implicit val userIdTypeBinder: TypeBinder[WorkbenchUserId] = new TypeBinder[WorkbenchUserId] {
    def apply(rs: ResultSet, label: String): WorkbenchUserId = WorkbenchUserId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchUserId = WorkbenchUserId(rs.getString(index))
  }

  implicit val emailTypeBinder: TypeBinder[WorkbenchEmail] = new TypeBinder[WorkbenchEmail] {
    def apply(rs: ResultSet, label: String): WorkbenchEmail = WorkbenchEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchEmail = WorkbenchEmail(rs.getString(index))
  }

  implicit val googleSubjectIdTypeBinder: TypeBinder[GoogleSubjectId] = new TypeBinder[GoogleSubjectId] {
    def apply(rs: ResultSet, label: String): GoogleSubjectId = GoogleSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GoogleSubjectId = GoogleSubjectId(rs.getString(index))
  }
}
