package org.broadinstitute.dsde.workbench.sam.db

trait DatabaseKey {
  val value: Long

  override def toString: String = value.toString
}
