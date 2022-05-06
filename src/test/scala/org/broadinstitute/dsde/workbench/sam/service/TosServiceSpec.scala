package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, PostgresDirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.flatspec.AnyFlatSpec

import java.net.URI
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

class TosServiceSpec extends AnyFlatSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter {

  private lazy val directoryConfig = TestSupport.appConfig.directoryConfig
  private lazy val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  val regDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, global)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get

  private val tosServiceEnabledV0 = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = 0))
  private val tosServiceEnabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true))
  private val tosServiceEnabledV2 = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = 2))
  private val tosServiceDisabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = false))
  private val userServiceTosEnabledV0 = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceEnabledV0)
  private val userServiceTosEnabled = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceEnabled)
  private val userServiceTosEnabledV2 = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceEnabledV2)
  private val userServiceTosDisabled = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceDisabled)

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

  it should "generate the expected group name" in {
    assert(tosServiceEnabled.getGroupNameString(0) == "tos_accepted_0")
    assert(tosServiceEnabled.getGroupNameString(10) == "tos_accepted_10")
  }

  it should "create the group once" in {
    assert(tosServiceEnabled.getTosGroupName(samRequestContext).unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync().isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the group initially")
    val maybeGroup = tosServiceEnabled.getTosGroupName(samRequestContext).unsafeRunSync()
    assert(maybeGroup.isDefined, "ToS Group should exist after above call")
    assert(maybeGroup.get.value == "tos_accepted_0")
    assert(tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync().isDefined, "resetTermsOfServiceGroupsIfNeeded() should return the same response when called again")
  }

  it should "tolerate the race condition where the ToS group is created after checking to see if it exists" in {
    val mockDirDAO = mock[DirectoryDAO]
    val tosService = new TosService(mockDirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = 0))

    when(mockDirDAO.createGroup(any[BasicWorkbenchGroup], any[Option[String]], any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "group already exists!")(ErrorReportSource("sam")))))
    when(mockDirDAO.loadGroupEmail(any[WorkbenchGroupName], any[SamRequestContext])).thenReturn(IO(None))

    assert(tosService.getTosGroupName(samRequestContext).unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(tosService.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync().isDefined, "resetTermsOfServiceGroupsIfNeeded() should not fail if the group is created after it tries to load it")
  }

  it should "do nothing if ToS check is not enabled" in {
    assert(tosServiceDisabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext) == IO.none)
  }

  it should "accept, get, and reject the ToS for a user" in {
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the group initially")
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

  it should "empty the enabledUsers group in OpenDJ when the ToS version changes" in {
    //Reset the ToS groups (this will empty the enabled-users group, but it should already be empty)
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the group initially")

    //Create the user in the system
    Await.result(userServiceTosEnabled.createUser(defaultUser, samRequestContext), Duration.Inf)

    //As the above user, accept the ToS
    userServiceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Check if the user has accepted ToS
    val isEnabledViaToS = dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledViaToS, "dirDAO.isEnabled (first check) should have returned true")

    //Check if the user is enabled in LDAP
    val isEnabledLdap = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledLdap, "regDAO.isEnabled (first check) should have returned true")

    //Bump the ToS version and reset the ToS groups
    val group2 = tosServiceEnabledV2.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group2.isDefined, "resetTermsOfServiceGroupsIfNeeded() should re-create the group")

    //Ensure that the user is now disabled, because they haven't accepted the new ToS version
    val isEnabledLdapV2 = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = false, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapV2)

    //Lastly, let's make sure the user can get re-enabled when accepting the new ToS version
    userServiceTosEnabledV2.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Ensure that the user is now disabled, because they haven't accepted the new ToS version
    val isEnabledLdapV2PostAccept = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = true, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapV2PostAccept)

    //Delete the user from the system
    Await.result(userServiceTosEnabled.deleteUser(defaultUser.id, samRequestContext), Duration.Inf)
  }

  it should "not empty the enabledUsers group in OpenDJ when the ToS version remains the same" in {
    //Reset the ToS groups (this will empty the enabled-users group, but it should already be empty)
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the group initially")

    //Create the user in the system
    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()

    //Enable in Postgres
    dirDAO.enableIdentity(defaultUser.id, samRequestContext).unsafeRunSync()

    //As the above user, accept the ToS
    userServiceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Check if the user has accepted ToS
    val isEnabledViaToS = dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledViaToS, "dirDAO.isEnabled (first check) should have returned true")

    //Check if the user is enabled in LDAP
    val isEnabledLdap = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledLdap, "regDAO.isEnabled (first check) should have returned true")

    //Reset the ToS groups, which should be a no-op since the version remains the same
    val group2 = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()

    //Ensure that the user is NOT disabled, because we haven't changed the ToS version
    val isEnabledLdapPostReset = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = true, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapPostReset)
  }

  it should "not remove Service Accounts from the enabledUsers group in OpenDJ when the ToS version changes" in {
    //Reset the ToS groups (this will empty the enabled-users group, but it should already be empty)
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the group initially")

    //Create the users in the system
    Await.result(userServiceTosEnabled.createUser(defaultUser, samRequestContext), Duration.Inf)
    Await.result(userServiceTosEnabled.createUser(serviceAccountUser, samRequestContext), Duration.Inf)

    //As the above user, accept the ToS
    userServiceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Check if the user has accepted ToS and is enabled in Postgres
    val isEnabledViaToS = dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledViaToS, "dirDAO.isEnabled (first check) should have returned true [User]")

    //Check if the SA is enabled
    val isSAEnabledViaToS = dirDAO.isEnabled(serviceAccountUser.id, samRequestContext).unsafeRunSync()
    assert(isSAEnabledViaToS, "dirDAO.isEnabled (first check) should have returned true [SA]")

    //Check if the user is enabled in LDAP
    val isEnabledLdap = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledLdap, "regDAO.isEnabled (first check) should have returned true [User]")

    //Check if the SA is enabled in LDAP
    val isSAEnabledLdap = regDAO.isEnabled(serviceAccountUser.id, samRequestContext).unsafeRunSync()
    assert(isSAEnabledLdap, "regDAO.isEnabled (first check) should have returned true [SA]")

    //Bump the ToS version and reset the ToS groups
    val group2 = tosServiceEnabledV2.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group2.isDefined, "resetTermsOfServiceGroupsIfNeeded() should re-create the group")

    //Ensure that the user is now disabled, because they haven't accepted the new ToS version
    val isEnabledLdapV2 = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = false, "regDAO.isEnabled (second check) should have returned false [User]")(actual = isEnabledLdapV2)

    //Ensure that the SA is still enabled, because we don't disable SAs when the ToS version changes
    val isSAEnabledLdapV2 = regDAO.isEnabled(serviceAccountUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = true, "regDAO.isEnabled (second check) should have returned true [SA]")(actual = isSAEnabledLdapV2)
  }

  it should "not remove users from the enabledUsers group in OpenDJ when the ToS version is v0" in {
    //Create the users in the system with ToS disabled. This simulates the rollout to production
    Await.result(userServiceTosDisabled.createUser(defaultUser, samRequestContext), Duration.Inf)
    Await.result(userServiceTosDisabled.createUser(serviceAccountUser, samRequestContext), Duration.Inf)

    //Check if the user is enabled in LDAP
    val isEnabledLdap = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledLdap, "regDAO.isEnabled (first check) should have returned true [User]")

    //Check if the SA is enabled in LDAP
    val isSAEnabledLdap = regDAO.isEnabled(serviceAccountUser.id, samRequestContext).unsafeRunSync()
    assert(isSAEnabledLdap, "regDAO.isEnabled (first check) should have returned true [SA]")

    //Reset the ToS groups (this shouldn't do anything to the enabledUsers group, which we'll assert elsewhere below)
    val group = tosServiceEnabledV0.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the Postgres group initially")

    //Check if the user is enabled in LDAP
    val isEnabledLdapAfter = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(isEnabledLdapAfter, "regDAO.isEnabled (second check) should have returned true [User]")

    //Check if the SA is enabled in LDAP
    val isSAEnabledLdapAfter = regDAO.isEnabled(serviceAccountUser.id, samRequestContext).unsafeRunSync()
    assert(isSAEnabledLdapAfter, "regDAO.isEnabled (second check) should have returned true [SA]")
  }

  it should "throw when it fails to remove users from the enabledUsers group in OpenDJ" in {
    val mockRegDAO = mock[RegistrationDAO]
    val tosService = new TosService(dirDAO, mockRegDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = 1))

    when(mockRegDAO.disableAllHumanIdentities(any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport("Couldn't empty enabled-users")(ErrorReportSource("sam")))))

    intercept [WorkbenchExceptionWithErrorReport] {
      tosService.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    }
  }

  it should "should allow a user to accept ToS v0" in {
    //Create the users in the system with ToS disabled. This simulates the rollout to production
    Await.result(userServiceTosDisabled.createUser(defaultUser, samRequestContext), Duration.Inf)

    //Reset the ToS groups
    val group = tosServiceEnabledV0.resetTermsOfServiceGroupsIfNeeded(samRequestContext).unsafeRunSync()
    assert(group.isDefined, "resetTermsOfServiceGroupsIfNeeded() should create the Postgres group initially")

    //Check if the user has accepted ToS and is enabled in Postgres
    val isEnabledViaToS = tosServiceEnabledV0.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync().get
    assert(!isEnabledViaToS, "tosServiceEnabledV0.getTosStatus (first check) should have returned false [User]")

    //As the above user, accept the ToS
    userServiceTosEnabledV0.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Check if the user has accepted ToS and is enabled in Postgres
    val isEnabledViaToSAfter = tosServiceEnabledV0.getTosStatus(defaultUser.id, samRequestContext).unsafeRunSync().get
    assert(isEnabledViaToSAfter, "tosServiceEnabledV0.getTosStatus (second check) should have returned true [User]")
  }

}
