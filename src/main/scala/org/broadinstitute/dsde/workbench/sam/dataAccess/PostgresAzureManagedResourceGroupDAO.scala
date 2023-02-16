package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.azure.{BillingProfileId, ManagedResourceGroup, ManagedResourceGroupCoordinates}
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, PSQLStateExtensions}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db.tables.{AzureManagedResourceGroupRecord, AzureManagedResourceGroupTable}
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.postgresql.util.PSQLException

import scala.util.{Failure, Try}

class PostgresAzureManagedResourceGroupDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)
    extends AzureManagedResourceGroupDAO
    with DatabaseSupport {

  override def insertManagedResourceGroup(managedResourceGroup: ManagedResourceGroup, samRequestContext: SamRequestContext): IO[Int] =
    serializableWriteTransaction("insertManagedResourceGroup", samRequestContext) { implicit session =>
      val mrgTableColumn = AzureManagedResourceGroupTable.column
      val insertGroupQuery =
        samsql"""insert into ${AzureManagedResourceGroupTable.table} (${mrgTableColumn.tenantId}, ${mrgTableColumn.subscriptionId}, ${mrgTableColumn.managedResourceGroupName}, ${mrgTableColumn.billingProfileId})
           values (${managedResourceGroup.managedResourceGroupCoordinates.tenantId}, ${managedResourceGroup.managedResourceGroupCoordinates.subscriptionId}, ${managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName}, ${managedResourceGroup.billingProfileId})"""
      Try {
        insertGroupQuery.update().apply()
      }.recoverWith {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
          Failure(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.Conflict, s"Coordinates or billing profile of ${managedResourceGroup} already exists", duplicateException)
            )
          )
      }.get
    }

  override def getManagedResourceGroupByBillingProfileId(
      billingProfileId: BillingProfileId,
      samRequestContext: SamRequestContext
  ): IO[Option[ManagedResourceGroup]] =
    readOnlyTransaction("getManagedResourceGroupByBillingProfileId", samRequestContext) { implicit session =>
      val mrg = AzureManagedResourceGroupTable.syntax("mrg")

      samsql"""select ${mrg.result.*}
              from ${AzureManagedResourceGroupTable as mrg}
              where ${mrg.billingProfileId} = ${billingProfileId}"""
        .map(AzureManagedResourceGroupTable.apply(mrg))
        .single()
        .apply()
        .map(toManagedResourceGroup)
    }

  override def getManagedResourceGroupByCoordinates(
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext
  ): IO[Option[ManagedResourceGroup]] =
    readOnlyTransaction("getManagedResourceGroupByCoordinates", samRequestContext) { implicit session =>
      val mrg = AzureManagedResourceGroupTable.syntax("mrg")

      samsql"""select ${mrg.result.*}
              from ${AzureManagedResourceGroupTable as mrg}
              where ${mrg.tenantId} = ${mrgCoords.tenantId}
              and ${mrg.subscriptionId} = ${mrgCoords.subscriptionId}
              and ${mrg.managedResourceGroupName} = ${mrgCoords.managedResourceGroupName}"""
        .map(AzureManagedResourceGroupTable.apply(mrg))
        .single()
        .apply()
        .map(toManagedResourceGroup)
    }

  private def toManagedResourceGroup(result: AzureManagedResourceGroupRecord) =
    ManagedResourceGroup(
      ManagedResourceGroupCoordinates(
        result.tenantId,
        result.subscriptionId,
        result.managedResourceGroupName
      ),
      result.billingProfileId
    )

  override def deleteManagedResourceGroup(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Int] =
    serializableWriteTransaction("deleteManagedResourceGroup", samRequestContext) { implicit session =>
      val mrg = AzureManagedResourceGroupTable.syntax("mrg")

      samsql"""delete
              from ${AzureManagedResourceGroupTable as mrg}
              where ${mrg.billingProfileId} = ${billingProfileId}"""
        .map(AzureManagedResourceGroupTable.apply(mrg))
        .update()
        .apply()
    }
}
