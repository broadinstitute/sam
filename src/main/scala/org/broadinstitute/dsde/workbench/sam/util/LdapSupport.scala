package org.broadinstitute.dsde.workbench.sam.util

import com.unboundid.ldap.sdk._

import scala.util.{Failure, Success, Try}

trait LdapSupport {
  protected val ldapConnectionPool: LDAPConnectionPool
  protected val batchSize = 1000

  /**
    * Call this to perform an ldap search.
    *
    * This function makes use of Unboundid's asynchronous and buffered loading of search results. That's why it
    * returns a Stream. The LDAPEntrySource used internally limits the number of items pulled back from ldap
    * to avoid memory overrun and the use of Stream passes that behavior up to the caller. If the results are
    * possibly large try not to read them all in to memory (e.g. by calling toSet).
    *
    * @param baseDn dn to start the search
    * @param searchScope depth of search
    * @param filters Can accept more than one filter to perform serially for example in the case of batched queries.
    *                The matches for all filters are returned (logical OR). 0 filters, 0 results.
    * @param unmarshaller transforms each matched Entry to the object of your choice
    * @tparam T
    * @return
    */
  protected def ldapSearchStream[T](baseDn: String, searchScope: SearchScope, filters: Filter*)(unmarshaller: Entry => T): Stream[T] = {
    filters.flatMap { filter =>
      val search = new SearchRequest(baseDn, searchScope, filter)
      val entrySource = new LDAPEntrySource(ldapConnectionPool.getConnection, search, true)
      ldapEntrySourceStream(entrySource)(unmarshaller)
    }.toStream
  }

  // this is the magic recursive stream generator
  private def ldapEntrySourceStream[T](entrySource: LDAPEntrySource)(unmarshaller: Entry => T): Stream[T] = {
    Try(Option(entrySource.nextEntry)) match {
      case Success(None) =>
        // reached the last element, Stream.empty terminates the stream
        Stream.empty

      case Success(Some(next)) =>
        // next element exists, return a Stream starting with unmarshalled next followed by the rest of the stream
        // (streams are smart and lazily evaluate the second parameter)
        Stream.cons(unmarshaller(next), ldapEntrySourceStream(entrySource)(unmarshaller))

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
