package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class PetServiceAccountGoogleProject(value: String) extends ValueObject
final case class PetServiceAccountGoogleSubjectId(value: String) extends ValueObject
final case class PetServiceAccountEmail(value: String) extends ValueObject
final case class PetServiceAccountRecord(userId: UserId,
                                         project: PetServiceAccountGoogleProject,
                                         googleSubjectId: PetServiceAccountGoogleSubjectId,
                                         email: PetServiceAccountEmail)

object PetServiceAccountRecord extends SQLSyntaxSupport[PetServiceAccountRecord] {
  override def tableName: String = "SAM_PET_SERVICE_ACCOUNT"

  import PetServiceAccountRecordBinders._
  import UserRecordBinders._
  def apply(e: ResultName[PetServiceAccountRecord])(rs: WrappedResultSet): PetServiceAccountRecord = PetServiceAccountRecord(
    rs.get(e.userId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email)
  )
}

object PetServiceAccountRecordBinders {
  implicit val petServiceAccountGoogleProjectTypeBinder: TypeBinder[PetServiceAccountGoogleProject] = new TypeBinder[PetServiceAccountGoogleProject] {
    def apply(rs: ResultSet, label: String): PetServiceAccountGoogleProject = PetServiceAccountGoogleProject(rs.getString(label))
    def apply(rs: ResultSet, index: Int): PetServiceAccountGoogleProject = PetServiceAccountGoogleProject(rs.getString(index))
  }

  implicit val petServiceAccountGoogleSubjectIdTypeBinder: TypeBinder[PetServiceAccountGoogleSubjectId] = new TypeBinder[PetServiceAccountGoogleSubjectId] {
    def apply(rs: ResultSet, label: String): PetServiceAccountGoogleSubjectId = PetServiceAccountGoogleSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): PetServiceAccountGoogleSubjectId = PetServiceAccountGoogleSubjectId(rs.getString(index))
  }

  implicit val petServiceAccountEmailTypeBinder: TypeBinder[PetServiceAccountEmail] = new TypeBinder[PetServiceAccountEmail] {
    def apply(rs: ResultSet, label: String): PetServiceAccountEmail = PetServiceAccountEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): PetServiceAccountEmail = PetServiceAccountEmail(rs.getString(index))
  }
}
