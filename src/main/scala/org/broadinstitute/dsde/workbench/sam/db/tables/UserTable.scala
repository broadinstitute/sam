package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class UserId(value: Long) extends DatabaseId
final case class UserEmail(value: String) extends ValueObject
final case class UserGoogleSubjectId(value: String) extends ValueObject
final case class UserRecord(id: UserId,
                            email: UserEmail,
                            googleSubjectId: Option[UserGoogleSubjectId])

object UserRecord extends SQLSyntaxSupport[UserRecord] {
  override def tableName: String = "SAM_USER"

  import UserRecordBinders._
  def apply(e: ResultName[UserRecord])(rs: WrappedResultSet): UserRecord = UserRecord(
    rs.get(e.id),
    rs.get(e.email),
    rs.get(e.googleSubjectId)
  )
}

object UserRecordBinders {
  implicit val userIdTypeBinder: TypeBinder[UserId] = new TypeBinder[UserId] {
    def apply(rs: ResultSet, label: String): UserId = UserId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): UserId = UserId(rs.getLong(index))
  }

  implicit val userEmailTypeBinder: TypeBinder[UserEmail] = new TypeBinder[UserEmail] {
    def apply(rs: ResultSet, label: String): UserEmail = UserEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): UserEmail = UserEmail(rs.getString(index))
  }

  implicit val userGoogleSubjectIdTypeBinder: TypeBinder[UserGoogleSubjectId] = new TypeBinder[UserGoogleSubjectId] {
    def apply(rs: ResultSet, label: String): UserGoogleSubjectId = UserGoogleSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): UserGoogleSubjectId = UserGoogleSubjectId(rs.getString(index))
  }
}
