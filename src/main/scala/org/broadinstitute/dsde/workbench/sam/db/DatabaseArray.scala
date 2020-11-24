package org.broadinstitute.dsde.workbench.sam.db

import java.sql
import java.sql.Connection

import scalikejdbc.ConnectionPool

// adaptor for Postgres Array types.
// Subclasses must define their own base type and how to represent themselves as Java arrays
trait DatabaseArray {
  protected val baseTypeName: String
  protected def asJavaArray: Array[Object]

  // TODO is there a better way to get a DB connection?
  private def getDbConnection: Connection = ConnectionPool.borrow(DatabaseNames.Foreground.name)

  def asSqlArray: sql.Array = getDbConnection.createArrayOf(baseTypeName, asJavaArray)

  override def toString: String = asJavaArray.mkString("Array(", ", ", ")")
}