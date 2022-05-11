package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.unsafe.implicits.{global => globalEc}
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.flatspec.AnyFlatSpec

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFlatSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter with PropertyBasedTesting {

  private lazy val directoryConfig = TestSupport.appConfig.directoryConfig
  private lazy val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  val regDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, global)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get

  private val tosServiceEnabledV0 = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = "0"))
  private val tosServiceEnabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true))
  private val tosServiceEnabledV2 = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = "2"))
  private val tosServiceDisabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = false))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSupport.truncateAll
  }

  before {
    clearDatabase()
    TestSupport.truncateAll
    runAndWait(regDAO.createEnabledUsersGroup(samRequestContext).unsafeToFuture())
  }

  protected def clearDatabase(): Unit = {
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    TestSupport.truncateAll
  }

  "TosService" should "accept, get, and reject the ToS for a user" in {
    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()

    // accept and get ToS status
    val acceptTosStatusResult = tosServiceEnabled.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(acceptTosStatusResult.get, s"acceptTosStatus(${defaultUser.id}) should accept the tos for the user")
    val getTosStatusResult = tosServiceEnabled.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(getTosStatusResult.get, s"getTosStatus(${defaultUser.id}) should get the tos for the user")

    // reject and get ToS status
    val rejectTosStatusResult = tosServiceEnabled.rejectTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(rejectTosStatusResult.get, s"rejectTosStatus(${defaultUser.id}) should reject the tos for the user")
    val getTosStatusResultRejected = tosServiceEnabled.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = false, s"getTosStatus(${defaultUser.id}) should have returned false")(actual = getTosStatusResultRejected.get)
  }

  it should "accept new version of ToS" in {
    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()

    tosServiceEnabledV0.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(false)
    tosServiceEnabledV0.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(true)
    tosServiceEnabledV0.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(true)

    tosServiceEnabledV2.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(false)
    tosServiceEnabledV2.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(true)
    tosServiceEnabledV2.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Option(true)
  }
}
