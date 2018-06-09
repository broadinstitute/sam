package org.broadinstitute.dsde.workbench.sam.util

import com.unboundid.ldap.sdk._

trait LdapSupport {
  protected val ldapConnectionPool: LDAPConnectionPool
  protected val batchSize = 1000

  protected def ldapSearchStream[T](baseDn: String, searchScope: SearchScope, filters: Filter*)(unmarshaller: Entry => T): Stream[T] = {
    filters.flatMap { filter =>
      val search = new SearchRequest(baseDn, searchScope, filter)
      val entrySource = new LDAPEntrySource(ldapConnectionPool.getConnection, search, true)
      ldapEntrySourceStream(entrySource)(unmarshaller)
    }.toStream
  }

  protected def ldapEntrySourceStream[T](entrySource: LDAPEntrySource)(unmarshaller: Entry => T): Stream[T] = {
    Option(entrySource.nextEntry) match {
      case None => Stream.empty
      case Some(next) => Stream.cons(unmarshaller(next), ldapEntrySourceStream(entrySource)(unmarshaller))
    }
  }

  protected def getAttribute(results: Entry, key: String): Option[String] = {
    Option(results.getAttribute(key)).map(_.getValue)
  }

  protected def getAttributes(results: Entry, key: String): Option[TraversableOnce[String]] = {
    Option(results.getAttribute(key)).map(_.getValues)
  }


}
