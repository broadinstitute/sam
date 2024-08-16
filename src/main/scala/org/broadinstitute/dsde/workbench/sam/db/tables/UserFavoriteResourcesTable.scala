package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

import java.time.Instant

final case class UserFavoriteResourcesRecord(
    samUserId: WorkbenchUserId,
    resourceId: ResourcePK,
    createdAt: Instant
)

object UserFavoriteResourcesTable extends SQLSyntaxSupportWithDefaultSamDB[UserFavoriteResourcesRecord] {
  override def tableName: String = "SAM_USER_FAVORITE_RESOURCES"

  import SamTypeBinders._
  def apply(e: ResultName[UserFavoriteResourcesRecord])(rs: WrappedResultSet): UserFavoriteResourcesRecord = UserFavoriteResourcesRecord(
    rs.get(e.samUserId),
    rs.get(e.resourceId),
    rs.get(e.createdAt)
  )

  def apply(o: SyntaxProvider[UserFavoriteResourcesRecord])(rs: WrappedResultSet): UserFavoriteResourcesRecord = apply(o.resultName)(rs)
}
