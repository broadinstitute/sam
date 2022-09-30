package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, PostgresDirectoryDAO}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.flatspec.AnyFlatSpec

import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFlatSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter with PropertyBasedTesting {

  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)

  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get

  private val tosServiceEnabledV0 = new TosService(dirDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = "0"))
  private val tosServiceEnabled = new TosService(dirDAO, "example.com", TestSupport.tosConfig.copy(enabled = true))
  private val tosServiceEnabledV2 = new TosService(dirDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = "2"))
  private val tosServiceDisabled = new TosService(dirDAO, "example.com", TestSupport.tosConfig.copy(enabled = false))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSupport.truncateAll
  }

  before {
    clearDatabase()
    TestSupport.truncateAll
  }

  protected def clearDatabase(): Unit =
    TestSupport.truncateAll

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
