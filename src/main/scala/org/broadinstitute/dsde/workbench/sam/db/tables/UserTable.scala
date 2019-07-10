package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import scalikejdbc._

final case class UserId(value: String) extends ValueObject
final case class UserEmail(value: String) extends ValueObject
final case class UserGoogleSubjectId(value: String) extends ValueObject
final case class UserRecord(id: UserId,
                            email: UserEmail,
                            googleSubjectId: Option[UserGoogleSubjectId])

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
  implicit val userIdTypeBinder: TypeBinder[UserId] = new TypeBinder[UserId] {
    def apply(rs: ResultSet, label: String): UserId = UserId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): UserId = UserId(rs.getString(index))
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
