package org.broadinstitute.dsde.workbench.sam.db

/**
  * Extra PostgreSQL Error Codes as described:  https://www.postgresql.org/docs/11/errcodes-appendix.html
  * These are in addition to https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/util/PSQLState.java
  * Here's an issue of someone requesting to add all the codes: https://github.com/pgjdbc/pgjdbc/issues/534
  */
object PSQLStateExtensions {
  val UNIQUE_VIOLATION = "23505"
  val FOREIGN_KEY_VIOLATION = "23503"
  val NULL_CONSTRAINT_VIOLATION = "23502"
}
