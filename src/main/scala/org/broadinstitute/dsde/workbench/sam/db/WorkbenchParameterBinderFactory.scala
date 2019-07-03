package org.broadinstitute.dsde.workbench.sam.db

import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc.ParameterBinderFactory

// These aren't used yet, but we will want these for the queries in the DAOs
object WorkbenchParameterBinderFactory {
  implicit val databaseIdPbf: ParameterBinderFactory[DatabaseId] = ParameterBinderFactory[DatabaseId] {
    value => (stmt, idx) => stmt.setLong(idx, value.value)
  }

  implicit val valueObjectPbf: ParameterBinderFactory[ValueObject] = ParameterBinderFactory[ValueObject] {
    value => (stmt, idx) => stmt.setString(idx, value.value)
  }
}
