package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseArray, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.ServiceAgent
import scalikejdbc._

import java.time.Instant

final case class PetServiceAgentsRecord(
    samUserId: WorkbenchUserId,
    petServiceAccountProject: GoogleProject,
    destinationProject: GoogleProject,
    destinationProjectNumber: Long,
    serviceAgents: Set[String],
    updatedAt: Instant
)

final case class ServiceAgentsArray(serviceAgents: Set[ServiceAgent]) extends DatabaseArray {
  override val baseTypeName: String = "VARCHAR"
  override def asJavaArray: Array[Object] = serviceAgents.toArray.map(_.name.asInstanceOf[Object])
}

object PetServiceAgentsTable extends SQLSyntaxSupportWithDefaultSamDB[PetServiceAgentsRecord] {
  override def tableName: String = "SAM_PET_SERVICE_AGENTS"

  import SamTypeBinders._

  def apply(e: ResultName[PetServiceAgentsRecord])(rs: WrappedResultSet): PetServiceAgentsRecord = PetServiceAgentsRecord(
    rs.get(e.samUserId),
    rs.get(e.petServiceAccountProject),
    rs.get(e.destinationProject),
    rs.get(e.destinationProjectNumber),
    rs.get(e.serviceAgents),
    rs.get(e.updatedAt)
  )

  def apply(p: SyntaxProvider[PetServiceAgentsRecord])(rs: WrappedResultSet): PetServiceAgentsRecord = apply(p.resultName)(rs)
}
