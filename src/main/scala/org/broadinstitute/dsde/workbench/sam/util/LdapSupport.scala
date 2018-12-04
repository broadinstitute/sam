package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import com.unboundid.ldap.sdk._

import scala.concurrent.ExecutionContext

trait LdapSupport {
  protected val ldapConnectionPool: LDAPConnectionPool
  protected val batchSize = 1000
  protected val ecForLdapBlockingIO: ExecutionContext
  implicit protected val cs: ContextShift[IO]

  protected def executeLdap[A](ioa: IO[A]): IO[A] = cs.evalOn(ecForLdapBlockingIO)(ioa)

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
  protected def ldapSearchStream[T](baseDn: String, searchScope: SearchScope, filters: Filter*)(unmarshaller: Entry => IO[T]): fs2.Stream[IO, T] = {
    for {
      filter <- fs2.Stream(filters:_*)
      entry <- ldapSearch(baseDn, searchScope, filter)
      unmarshalled <- fs2.Stream.eval(unmarshaller(entry))
    } yield {
      unmarshalled
    }
  }

  private def ldapSearch(baseDn: String, searchScope: SearchScope, filter: Filter): fs2.Stream[IO, Entry] = {
    val search = new SearchRequest(baseDn, searchScope, filter)
    for {
      entrySource <- fs2.Stream.eval(executeLdap(IO(
        new LDAPEntrySource(ldapConnectionPool.getConnection, search, true))))

      entry <- fs2.Stream.unfoldEval[IO, LDAPEntrySource, Entry](entrySource) { entrySourceInner =>
        nextEntry(entrySourceInner).map(_.map(entry => (entry, entrySourceInner)))
      }
    } yield entry
  }

  private def nextEntry(entrySource: LDAPEntrySource): IO[Option[Entry]] = {
    executeLdap(IO(Option(entrySource.nextEntry))).handleErrorWith {
      case ldape: EntrySourceException
        if ldape.getCause.isInstanceOf[LDAPException] &&
          ldape.getCause.asInstanceOf[LDAPException].getResultCode == ResultCode.NO_SUCH_OBJECT =>
        IO.pure(None)
    }
  }

  protected def getAttribute(results: Entry, key: String): Option[String] =
    Option(results.getAttribute(key)).map(_.getValue)

  protected def getAttributes(results: Entry, key: String): Set[String] =
    Option(results.getAttribute(key)).map(_.getValues.toSet).getOrElse(Set.empty)
}
