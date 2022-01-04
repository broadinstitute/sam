package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, PostgresDirectoryDAO}
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
  val regDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, global)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  val defaultUserId = genWorkbenchUserId(System.currentTimeMillis())
  val defaultGoogleSubjectId = GoogleSubjectId(defaultUserId.value)
  val defaultUserEmail = WorkbenchEmail("fake@tosServiceSpec.com")
  val defaultUser = WorkbenchUser(defaultUserId, Option(defaultGoogleSubjectId), defaultUserEmail, None)


  private val tosServiceEnabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true))
  private val tosServiceDisabled = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = false))
  private val tosServiceEnabledV2 = new TosService(dirDAO, regDAO, "example.com", TestSupport.tosConfig.copy(enabled = true, version = 2))
  private val userServiceTosEnabled = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceEnabled)
  private val userServiceTosEnabledV2 = new UserService(dirDAO, NoExtensions, regDAO, Seq.empty, tosServiceEnabledV2)

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
    //Ensure that the enabled-users group exists in LDAP
    regDAO.createEnabledUsersGroup(samRequestContext).unsafeRunSync()

    assert(tosServiceEnabled.getTosGroup().unsafeRunSync().isEmpty, "ToS Group should not exist at the start")
    assert(tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync().isDefined, "createGroupIfNeeded() should create the group initially")
    val maybeGroup = tosServiceEnabled.getTosGroup().unsafeRunSync()
    assert(maybeGroup.isDefined, "ToS Group should exist after above call")
    assert(maybeGroup.get.id.value == "tos_accepted_0")
    assert(maybeGroup.get.email.value == "GROUP_tos_accepted_0@example.com")
    assert(tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync().isDefined, "createNewGroupIfNeeded() should return the same response when called again")
  }

  it should "do nothing if ToS check is not enabled" in {
    assert(tosServiceDisabled.resetTermsOfServiceGroupsIfNeeded() == IO.none)
  }

  it should "accept and get the ToS for a user" in {
    //Ensure that the enabled-users group exists in LDAP
    regDAO.createEnabledUsersGroup(samRequestContext).unsafeRunSync()

    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync()
    assert(group.isDefined, "createGroupIfNeeded() should create the group initially")
    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()
    val acceptTosStatusResult = tosServiceEnabled.acceptTosStatus(defaultUser.id).unsafeRunSync()
    assert(acceptTosStatusResult.get, s"acceptTosStatus(${defaultUser.id}) should accept the tos for the user")
    val getTosStatusResult = tosServiceEnabled.getTosStatus(defaultUser.id).unsafeRunSync()
    assert(getTosStatusResult.get, s"getTosStatus(${defaultUser.id}) should get the tos for the user")
  }

  it should "empty the enabledUsers group in OpenDJ when the ToS version changes" in {
    //Ensure that the enabled-users group exists in LDAP
    regDAO.createEnabledUsersGroup(samRequestContext).unsafeRunSync()

    //Reset the ToS groups (this will empty the enabled-users group, but it should already be empty)
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync()
    assert(group.isDefined, "createGroupIfNeeded() should create the group initially")

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

    //Bump the ToS version and reset the ToS groups
    val group2 = tosServiceEnabledV2.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync()
    assert(group2.isDefined, "createGroupIfNeeded() should re-create the group")

    //Ensure that the user is now disabled, because they haven't accepted the new ToS version
    val isEnabledLdapV2 = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = false, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapV2)

    //Lastly, let's make sure the user can get re-enabled when accepting the new ToS version
    userServiceTosEnabledV2.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    //Ensure that the user is now disabled, because they haven't accepted the new ToS version
    val isEnabledLdapV2PostAccept = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = true, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapV2PostAccept)
  }

  it should "not empty the enabledUsers group in OpenDJ when the ToS version remains the same" in {
    //Ensure that the enabled-users group exists in LDAP
    regDAO.createEnabledUsersGroup(samRequestContext).unsafeRunSync()

    //Reset the ToS groups (this will empty the enabled-users group, but it should already be empty)
    val group = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync()
    assert(group.isDefined, "createGroupIfNeeded() should create the group initially")

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
    val group2 = tosServiceEnabled.resetTermsOfServiceGroupsIfNeeded().unsafeRunSync()

    //Ensure that the user is NOT disabled, because we haven't changed the ToS version
    val isEnabledLdapPostReset = regDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync()
    assertResult(expected = true, "regDAO.isEnabled (second check) should have returned false")(actual = isEnabledLdapPostReset)
  }

}
