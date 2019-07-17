package org.broadinstitute.dsde.workbench.sam.db

import org.broadinstitute.dsde.workbench.model.ValueObject
import scalikejdbc.ParameterBinderFactory

object SamParameterBinderFactory {
  implicit def databaseKeyPbf[T <: DatabaseKey]: ParameterBinderFactory[T] = ParameterBinderFactory[T] {
    value => (stmt, idx) => stmt.setLong(idx, value.value)
  }

  implicit def valueObjectPbf[T <: ValueObject]: ParameterBinderFactory[T] = ParameterBinderFactory[T] {
    value => (stmt, idx) => stmt.setString(idx, value.value)
  }
}
