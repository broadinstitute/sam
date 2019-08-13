package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import scalikejdbc._

final case class ResourceTypePK(value: Long) extends DatabaseKey
final case class ResourceTypeRecord(id: ResourceTypePK,
                                    name: ResourceTypeName)

object ResourceTypeTable extends SQLSyntaxSupportWithDefaultSamDB[ResourceTypeRecord] {
  override def tableName: String = "SAM_RESOURCE_TYPE"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceTypeRecord])(rs: WrappedResultSet): ResourceTypeRecord = ResourceTypeRecord(
    rs.get(e.id),
    rs.get(e.name)
  )

  def pkQuery(resourceTypeName: ResourceTypeName, resourceTypeTableAlias: String = "rtt"): SQLSyntax = {
    val rtt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    samsqls"select ${rtt.id} from ${ResourceTypeTable as rtt} where ${rtt.name} = ${resourceTypeName}"
  }
}
