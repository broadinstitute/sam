package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import scalikejdbc._

final case class PetServiceAccountRecord(userId: WorkbenchUserId,
                                         project: GoogleProject,
                                         googleSubjectId: GoogleSubjectId,
                                         email: WorkbenchEmail)

object PetServiceAccountTable extends SQLSyntaxSupport[PetServiceAccountRecord] {
  override def tableName: String = "SAM_PET_SERVICE_ACCOUNT"

  import PetServiceAccountTableBinders._
  import UserTableBinders._
  def apply(e: ResultName[PetServiceAccountRecord])(rs: WrappedResultSet): PetServiceAccountRecord = PetServiceAccountRecord(
    rs.get(e.userId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email)
  )
}

object PetServiceAccountTableBinders {
  implicit val petServiceAccountGoogleProjectTypeBinder: TypeBinder[GoogleProject] = new TypeBinder[GoogleProject] {
    def apply(rs: ResultSet, label: String): GoogleProject = GoogleProject(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GoogleProject = GoogleProject(rs.getString(index))
  }

  implicit val petServiceAccountGoogleSubjectIdTypeBinder: TypeBinder[GoogleSubjectId] = new TypeBinder[GoogleSubjectId] {
    def apply(rs: ResultSet, label: String): GoogleSubjectId = GoogleSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GoogleSubjectId = GoogleSubjectId(rs.getString(index))
  }

  implicit val petServiceAccountEmailTypeBinder: TypeBinder[WorkbenchEmail] = new TypeBinder[WorkbenchEmail] {
    def apply(rs: ResultSet, label: String): WorkbenchEmail = WorkbenchEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchEmail = WorkbenchEmail(rs.getString(index))
  }
}
