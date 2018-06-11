package org.broadinstitute.dsde.workbench.sam.util

import com.unboundid.ldap.sdk._

import scala.util.{Failure, Success, Try}

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

  private def ldapEntrySourceStream[T](entrySource: LDAPEntrySource)(unmarshaller: Entry => T): Stream[T] = {
    Try(Option(entrySource.nextEntry)) match {
      case Success(None) => Stream.empty
      case Success(Some(next)) => Stream.cons(unmarshaller(next), ldapEntrySourceStream(entrySource)(unmarshaller))
      case Failure(ldape: EntrySourceException) if ldape.getCause.isInstanceOf[LDAPException] &&
        ldape.getCause.asInstanceOf[LDAPException].getResultCode == ResultCode.NO_SUCH_OBJECT => Stream.empty // the base dn does not exist, treat as empty search
      case Failure(regrets) => throw regrets
    }
  }

  protected def getAttribute(results: Entry, key: String): Option[String] = {
    Option(results.getAttribute(key)).map(_.getValue)
  }

  protected def getAttributes(results: Entry, key: String): Option[TraversableOnce[String]] = {
    Option(results.getAttribute(key)).map(_.getValues)
  }


}
