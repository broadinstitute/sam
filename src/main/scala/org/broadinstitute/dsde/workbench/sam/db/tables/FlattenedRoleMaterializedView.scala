package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class FlattenedRoleRecord(baseRoleId: ResourceRolePK,
                                     nestedRoleId: ResourceRolePK,
                                     descendantsOnly: Boolean)

/** This is actually a materialized view (see https://www.postgresql.org/docs/9.6/rules-materializedviews.html
  * for details) not a table. However, this case class and object allow us to reference the materialized view
  * using the same scalike syntax that we use for real tables. */
object FlattenedRoleMaterializedView extends SQLSyntaxSupportWithDefaultSamDB[FlattenedRoleRecord] {
  override def tableName: String = "SAM_FLATTENED_ROLE"
  // need to specify column names explicitly because scalike doesn't like materialized views as much as it likes tables
  override val columnNames: Seq[String] = Seq("base_role_id", "nested_role_id", "descendants_only")

  import SamTypeBinders._
  def apply(e: ResultName[FlattenedRoleRecord])(rs: WrappedResultSet): FlattenedRoleRecord = FlattenedRoleRecord(
    rs.get(e.baseRoleId),
    rs.get(e.nestedRoleId),
    rs.get(e.descendantsOnly)
  )
}
