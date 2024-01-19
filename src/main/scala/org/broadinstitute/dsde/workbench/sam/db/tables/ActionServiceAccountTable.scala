package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class ActionServiceAccountRecord(
    resourceId: ResourcePK,
    resourceActionId: ResourceActionPK,
    project: GoogleProject,
    googleSubjectId: ServiceAccountSubjectId,
    email: WorkbenchEmail,
    displayName: ServiceAccountDisplayName
)

object ActionServiceAccountTable extends SQLSyntaxSupportWithDefaultSamDB[ActionServiceAccountRecord] {
  override def tableName: String = "SAM_ACTION_SERVICE_ACCOUNT"

  import SamTypeBinders._
  def apply(e: ResultName[ActionServiceAccountRecord])(rs: WrappedResultSet): ActionServiceAccountRecord = ActionServiceAccountRecord(
    rs.get(e.resourceId),
    rs.get(e.resourceActionId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email),
    rs.get(e.displayName)
  )

  def apply(p: SyntaxProvider[ActionServiceAccountRecord])(rs: WrappedResultSet): ActionServiceAccountRecord = apply(p.resultName)(rs)
}
