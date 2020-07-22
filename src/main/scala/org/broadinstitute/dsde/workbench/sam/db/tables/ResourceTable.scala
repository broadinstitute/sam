package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import scalikejdbc._

final case class ResourcePK(value: Long) extends DatabaseKey
final case class ResourceRecord(id: ResourcePK,
                                name: ResourceId,
                                resourceTypeId: ResourceTypePK,
                                resourceParentId: Option[ResourcePK])

object ResourceTable extends SQLSyntaxSupportWithDefaultSamDB[ResourceRecord] {
  override def tableName: String = "SAM_RESOURCE"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceRecord])(rs: WrappedResultSet): ResourceRecord = ResourceRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.resourceTypeId),
    rs.longOpt(e.resourceParentId).map(ResourcePK)
  )

  def loadResourcePK(resource: FullyQualifiedResourceId): SQLSyntax = {
    val r = ResourceTable.syntax("r")
    val rt = ResourceTypeTable.syntax("rt")
    samsqls"select ${r.id} from ${ResourceTable as r} join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id} where ${r.name} = ${resource.resourceId} and ${rt.name} = ${resource.resourceTypeName}"
  }
}
