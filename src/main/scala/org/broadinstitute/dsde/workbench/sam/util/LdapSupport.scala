package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import com.unboundid.ldap.sdk._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO.Attr
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.traceIOWithContext

import scala.concurrent.ExecutionContext

trait LdapSupport {
  protected val ldapConnectionPool: LDAPConnectionPool
  protected val batchSize = 1000
  protected val ecForLdapBlockingIO: ExecutionContext
  implicit protected val cs: ContextShift[IO]


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


  protected def unmarshalUser(results: Entry): Either[String, WorkbenchUser] =
    for {
      uid <- getAttribute(results, Attr.uid).toRight(s"${Attr.uid} attribute missing")
      email <- getAttribute(results, Attr.email).toRight(s"${Attr.email} attribute missing")
    } yield WorkbenchUser(WorkbenchUserId(uid), getAttribute(results, Attr.googleSubjectId).map(GoogleSubjectId), WorkbenchEmail(email), None)

  protected def executeLdap[A](ioa: IO[Any], dbQueryName: String, samRequestContext: SamRequestContext): IO[Any] = {
    traceIOWithContext("ldap-" + dbQueryName, samRequestContext)(_ => cs.evalOn(ecForLdapBlockingIO)(ioa))
  }
}
