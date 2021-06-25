package org.broadinstitute.dsde.workbench.sam.dataAccess

object ConnectionType extends Enumeration {
  type ConnectionType = Value
  val LDAP, Postgres = Value
}
