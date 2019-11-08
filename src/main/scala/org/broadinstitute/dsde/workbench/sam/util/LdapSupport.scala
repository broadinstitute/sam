package org.broadinstitute.dsde.workbench.sam.util

import cats.data.OptionT
import cats.effect.{ContextShift, IO}
import cats.implicits._
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchGroupIdentity, WorkbenchGroupName, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.directory.DirectorySubjectNameSupport
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr
import org.ehcache.Cache

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

trait LdapSupport extends DirectorySubjectNameSupport {
  protected val ldapConnectionPool: LDAPConnectionPool
  protected val batchSize = 1000
  protected val ecForLdapBlockingIO: ExecutionContext
  implicit protected val cs: ContextShift[IO]
  protected val memberOfCache: Cache[WorkbenchSubject, Set[String]]

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
  protected def ldapSearchStream[T](baseDn: String, searchScope: SearchScope, filters: Filter*)(unmarshaller: Entry => T): Stream[T] =
    filters.flatMap { filter =>
      val search = new SearchRequest(baseDn, searchScope, filter)
      val connection = ldapConnectionPool.getConnection
      val closeConnection = false

      val entrySource = new LDAPEntrySource(connection, search, closeConnection)
      ldapEntrySourceStream(entrySource, connection)(unmarshaller)
    }.toStream

  // this is the magic recursive stream generator
  // releases connection back to the pool once the end of entrySource is reached
  private def ldapEntrySourceStream[T](entrySource: LDAPEntrySource, connection: LDAPConnection)(unmarshaller: Entry => T): Stream[T] =
    Try(Option(entrySource.nextEntry)) match {
      case Success(None) =>
        // reached the last element, Stream.empty terminates the stream
        ldapConnectionPool.releaseConnection(connection)
        Stream.empty

      case Success(Some(next)) =>
        // next element exists, return a Stream starting with unmarshalled next followed by the rest of the stream
        // (streams are smart and lazily evaluate the second parameter)
        Stream.cons(unmarshaller(next), ldapEntrySourceStream(entrySource, connection)(unmarshaller))

      case Failure(ldape: EntrySourceException)
          if ldape.getCause.isInstanceOf[LDAPException] &&
            ldape.getCause.asInstanceOf[LDAPException].getResultCode == ResultCode.NO_SUCH_OBJECT =>
        ldapConnectionPool.releaseConnection(connection)
        Stream.empty // the base dn does not exist, treat as empty search
      case Failure(regrets) =>
        ldapConnectionPool.releaseConnection(connection)
        throw regrets
    }

  protected def getAttribute(result: Entry, key: String): Option[String] = {
    for {
      searchResultEntry <- Option(result)
      attribute <- Option(searchResultEntry.getAttribute(key))
    } yield {
     attribute.getValue
    }
  }


  protected def getAttributes(results: Entry, key: String): Set[String] = {
    for {
      searchResultEntries <- Option(results)
      attributes <- Option(searchResultEntries.getAttribute(key))
    } yield {
      attributes.getValues.toSet
    }
  }.getOrElse(Set.empty)

  protected def ldapLoadMemberOf(subject: WorkbenchSubject): IO[Set[String]] =
    Option(memberOfCache.get(subject)) match {
      case None =>
        for {
          entry <- executeLdap(IO(ldapConnectionPool.getEntry(subjectDn(subject), Attr.memberOf)))
        } yield {
          val memberOfs = Option(entry).flatMap(e => Option(getAttributes(e, Attr.memberOf))).getOrElse(Set.empty)
          memberOfCache.put(subject, memberOfs)
          memberOfs
        }

      case Some(memberOfs) => IO.pure(memberOfs)
    }

  def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit] =
    IO.pure(memberOfCache.remove(subject))

  def ldapLoadGroup(groupId: WorkbenchGroupIdentity): IO[Option[BasicWorkbenchGroup]] = {
    val res = for {
      entry <- OptionT(executeLdap(IO(ldapConnectionPool.getEntry(groupDn(groupId)))).map(Option.apply))
      r <- OptionT.liftF(IO(unmarshalGroupThrow(entry)))
    } yield r

    res.value
  }

  def ldapLoadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = {
    val filters = groupNames.grouped(batchSize).map(batch => Filter.createORFilter(batch.map(g => Filter.createEqualityFilter(Attr.cn, g.value)).asJava)).toSeq

    executeLdap(IO(ldapSearchStream(groupsOu, SearchScope.SUB, filters: _*)(unmarshalGroupThrow)))
  }

//  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] =
//    for {
//      memberOf <- ldapLoadMemberOf(member)
//    } yield {
//      val memberships = memberOf.map(_.toLowerCase) //toLowerCase because the dn can have varying capitalization
//      memberships.contains(groupDn(groupId).toLowerCase)
//    }

// KCIBUL: follow up on lowercase with Doug, I don't think we need it b/c we're not looking at DNs anymore
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] = {
    for {
      group <- ldapLoadGroup(groupId)
      members = group.map(_.members).getOrElse(Set.empty[WorkbenchSubject])
      isDirectMember = members.contains(member)
      isMember <- if (isDirectMember) IO.pure(isDirectMember) else members.collect{case x:WorkbenchGroupIdentity => x}.toList.existsM(isGroupMember(_, member))
    } yield {
      isMember
    }
  }

  private def unmarshalGroup(results: Entry): Either[String, BasicWorkbenchGroup] =
    for {
      cn <- getAttribute(results, Attr.cn).toRight(s"${Attr.cn} attribute missing: ${results.getDN}")
      email <- getAttribute(results, Attr.email).toRight(s"${Attr.email} attribute missing: ${results.getDN}")
      memberDns = getAttributes(results, Attr.uniqueMember)
    } yield BasicWorkbenchGroup(WorkbenchGroupName(cn), memberDns.map(dnToSubject), WorkbenchEmail(email))

  private def unmarshalGroupThrow(results: Entry): BasicWorkbenchGroup = unmarshalGroup(results).fold(s => throw new WorkbenchException(s), identity)

  protected def executeLdap[A](ioa: IO[A]): IO[A] = cs.evalOn(ecForLdapBlockingIO)(ioa)
}
