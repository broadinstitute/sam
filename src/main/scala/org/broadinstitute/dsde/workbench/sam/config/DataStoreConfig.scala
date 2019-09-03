package org.broadinstitute.dsde.workbench.sam.config
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.config.DataStores.DataStore

case class DataStoreConfig(live: DataStore, shadow: Option[DataStore])

object DataStores {
  sealed trait DataStore
  case object OpenDJ extends DataStore
  case object Postgres extends DataStore

  def apply(dataStoreString: String): DataStore = {
    if (dataStoreString.equalsIgnoreCase("opendj")) {
      OpenDJ
    } else if (dataStoreString.equalsIgnoreCase("postgres")) {
      Postgres
    } else {
      throw new WorkbenchException(s"unsupported datastore [$dataStoreString], valid values are [postgres] and [opendj]")
    }
  }
}
