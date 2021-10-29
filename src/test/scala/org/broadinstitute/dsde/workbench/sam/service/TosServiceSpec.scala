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

  private val service = new TosService(dirDAO, "example.com", TestSupport.tosConfig)

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
    assert(service.getTosGroup(0).unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(service.createNewGroupIfNeeded(isEnabled = true).unsafeRunSync().isDefined, "createGroupIfNeeded(0) should create the group initially")
    val maybeGroup = service.getTosGroup(0).unsafeRunSync()
    assert(maybeGroup.isDefined, "ToS Group should exist after above call")
    assert(maybeGroup.get.id.value == "tos_accepted_0")
    assert(maybeGroup.get.email.value == "GROUP_tos_accepted_0@example.com")
    assert(service.createNewGroupIfNeeded(isEnabled = true).unsafeRunSync().isEmpty, "createNewGroupIfNeeded(0) should no-op the second time")
  }

  it should "do nothing if ToS check is not enabled" in {
    assert(service.createNewGroupIfNeeded(isEnabled = false) == IO.none)
  }

}
