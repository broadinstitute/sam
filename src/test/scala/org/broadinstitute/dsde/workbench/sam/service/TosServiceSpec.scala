package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockRegistrationDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
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
  val regDAO = new MockRegistrationDAO
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  val defaultUserId = genWorkbenchUserId(System.currentTimeMillis())
  val defaultGoogleSubjectId = GoogleSubjectId(defaultUserId.value)
  val defaultUserEmail = WorkbenchEmail("fake@tosServiceSpec.com")
  val defaultUser = WorkbenchUser(defaultUserId, Option(defaultGoogleSubjectId), defaultUserEmail, None)


  private val tosServiceEnabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true))
  private val tosServiceDisabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = false))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
    TestSupport.truncateAll
  }

  before {
    TestSupport.truncateAll
  }

  it should "generate the expected group name" in {
    assert(tosServiceEnabled.getGroupName(0) == "tos_accepted_0")
    assert(tosServiceEnabled.getGroupName(10) == "tos_accepted_10")
  }

  it should "create the group once" in {
    assert(tosServiceEnabled.getTosGroup().unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(tosServiceEnabled.resetTermsOfServiceGroups().unsafeRunSync().isDefined, "createGroupIfNeeded() should create the group initially")
    val maybeGroup = tosServiceEnabled.getTosGroup().unsafeRunSync()
    assert(maybeGroup.isDefined, "ToS Group should exist after above call")
    assert(maybeGroup.get.id.value == "tos_accepted_0")
    assert(maybeGroup.get.email.value == "GROUP_tos_accepted_0@example.com")
    assert(tosServiceEnabled.resetTermsOfServiceGroups().unsafeRunSync().isDefined, "createNewGroupIfNeeded() should return the same response when called again")
  }

  it should "empty the enabledUsers group when creating a new ToS Postgres group" in {
    assert(tosServiceEnabled.getTosGroup().unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(tosServiceEnabled.resetTermsOfServiceGroups().unsafeRunSync().isDefined, "createGroupIfNeeded() should create the group initially")
    val maybeGroup = tosServiceEnabled.getTosGroup().unsafeRunSync()
    assert(maybeGroup.isDefined, "ToS Group should exist after above call")
    assert(maybeGroup.get.id.value == "tos_accepted_0")
    assert(maybeGroup.get.email.value == "GROUP_tos_accepted_0@example.com")
    assert(tosServiceEnabled.resetTermsOfServiceGroups().unsafeRunSync().isDefined, "createNewGroupIfNeeded() should return the same response when called again")
  }

  it should "do nothing if ToS check is not enabled" in {
    assert(tosServiceDisabled.resetTermsOfServiceGroups() == IO.none)
  }

  it should "accept and get the ToS for a user" in {
    val group = tosServiceEnabled.resetTermsOfServiceGroups().unsafeRunSync()
    assert(group.isDefined, "createGroupIfNeeded() should create the group initially")
    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()
    val acceptTosStatusResult = tosServiceEnabled.acceptTosStatus(defaultUser.id).unsafeRunSync()
    assert(acceptTosStatusResult.get, s"acceptTosStatus(${defaultUser.id}) should accept the tos for the user")
    val getTosStatusResult = tosServiceEnabled.getTosStatus(defaultUser.id).unsafeRunSync()
    assert(getTosStatusResult.get, s"getTosStatus(${defaultUser.id}) should get the tos for the user")
  }

}
