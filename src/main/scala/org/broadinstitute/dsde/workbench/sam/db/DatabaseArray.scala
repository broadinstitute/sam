package org.broadinstitute.dsde.workbench.sam.db

import java.sql
import java.sql.Connection

// adaptor for Postgres Array types.
// Subclasses must define their own base type and how to represent themselves as Java arrays
trait DatabaseArray {
  protected val baseTypeName: String
  protected def asJavaArray: Array[Object]

  def asSqlArray(conn: Connection): sql.Array = conn.createArrayOf(baseTypeName, asJavaArray)

  override def toString: String = asJavaArray.mkString("Array(", ", ", ")")
}