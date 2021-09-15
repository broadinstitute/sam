package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.flatspec.AnyFlatSpec

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFlatSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter {

  private lazy val directoryConfig = TestSupport.appConfig.directoryConfig
  private lazy val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val service = new TosService(dirDAO)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
    TestSupport.truncateAll
  }

  before {
    TestSupport.truncateAll
  }

  it should "generate the expected group name" in {
    assert(service.getGroupName(0) == "tos_accepted_0")
    assert(service.getGroupName(10) == "tos_accepted_10")
  }

  it should "create the group once" in {
    assert(!service.groupExists(0), "ToS Group should not exist at the start")
    service.createNewGroupIfNeeded(0, isEnabled = true).unsafeRunSync()
    assert(service.groupExists(0), "ToS Group should exist after above call")
    assert(service.createNewGroupIfNeeded(0, isEnabled = true) == IO.unit, "createNewGroupIfNeeded(0) should no-op the second time")
  }

  it should "do nothing if ToS check is not enabled" in {
    assert(service.createNewGroupIfNeeded(0, isEnabled = false) == IO.unit)
  }

}
