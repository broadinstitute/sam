package org.broadinstitute.dsde.workbench.sam.db

import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc.ParameterBinderFactory

object SamParameterBinderFactory {
  implicit def databaseIdPbf[T <: DatabaseId]: ParameterBinderFactory[T] = ParameterBinderFactory[T] {
    value => (stmt, idx) => stmt.setLong(idx, value.value)
  }

  implicit def valueObjectPbf[T <: ValueObject]: ParameterBinderFactory[T] = ParameterBinderFactory[T] {
    value => (stmt, idx) => stmt.setString(idx, value.value)
  }
}
