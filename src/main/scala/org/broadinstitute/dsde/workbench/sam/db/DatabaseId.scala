package org.broadinstitute.dsde.workbench.sam.db

trait DatabaseId {
  val value: Long

  override def toString: String = value.toString
}
