package org.broadinstitute.dsde.workbench.sam
package service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{arbNonPetEmail => _, _}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs.{CreateUserSpec, GetUserStatusSpec, InviteUserSpec}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, OptionValues, Suite}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// TODO: continue breaking down old UserServiceSpec tests into nested suites
// See: https://www.scalatest.org/scaladoc/3.2.3/org/scalatest/Suite.html
class UserServiceSpec extends Suite {
  override def nestedSuites: IndexedSeq[Suite] =
    IndexedSeq(
      new CreateUserSpec,
      new InviteUserSpec,
      new GetUserStatusSpec,
      new OldUserServiceSpec,
      new OldUserServiceMockSpec
    )
}

// This test suite is deprecated.  It is still used and still has valid tests in it, but it should be broken out
// into smaller, more focused suites which should then be added to the `nestedSuites` of `UserServiceSpec`
// This class does not connect to a real database (hence "mock" in the name (naming is hard, don't judge me)), but its
// tests should still be broken out to individual Spec files and rewritten
class OldUserServiceMockSpec
    extends AnyFlatSpec
    with Matchers
    with TestSupport
    with MockitoSugar
    with PropertyBasedTesting
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalaFutures
    with OptionValues {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(5.seconds))
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  val defaultUser = genWorkbenchUserBoth.sample.get
  val enabledDefaultUserStatus = UserStatusBuilder(defaultUser)
    .withAllUsersGroup(true)
    .withLdap(true)
    .withGoogle(true)
    .withTosAccepted(true)
    .withAdminEnabled(true)
    .build

  lazy val petServiceAccountConfig = TestSupport.appConfig.googleConfig.get.petServiceAccountConfig

  var service: UserService = _
  var googleExtensions: GoogleExtensions = _
  var dirDAO: DirectoryDAO = _
  var mockTosService: TosService = _
  val blockedDomain = "blocked.domain.com"
  val enabledUser: SamUser = defaultUser.copy(enabled = true)
  val disabledUser: SamUser = defaultUser.copy(enabled = false)
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName("All_Users"), Set(), WorkbenchEmail("all_users@fake.com"))

  before {
    dirDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(Some(enabledUser)))
    when(dirDAO.loadSubjectFromGoogleSubjectId(defaultUser.googleSubjectId.get, samRequestContext)).thenReturn(IO(None))
    when(dirDAO.loadSubjectFromEmail(defaultUser.email, samRequestContext)).thenReturn(IO(Option(defaultUser.id)))
    when(dirDAO.createUser(defaultUser, samRequestContext)).thenReturn(IO(disabledUser))
    when(dirDAO.deleteUser(defaultUser.id, samRequestContext)).thenReturn(IO(()))
    when(dirDAO.enableIdentity(defaultUser.id, samRequestContext)).thenReturn(IO(()))
    when(dirDAO.disableIdentity(defaultUser.id, samRequestContext)).thenReturn(IO(()))
    when(dirDAO.addGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
    when(dirDAO.removeGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
    when(dirDAO.isGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
    when(dirDAO.isEnabled(defaultUser.id, samRequestContext)).thenReturn(IO(true))
    when(dirDAO.listUserDirectMemberships(defaultUser.id, samRequestContext)).thenReturn(IO(LazyList(allUsersGroup.id)))
    when(dirDAO.setGoogleSubjectId(defaultUser.id, defaultUser.googleSubjectId.get, samRequestContext)).thenReturn(IO(()))
    when(dirDAO.setUserAzureB2CId(defaultUser.id, defaultUser.azureB2CId.get, samRequestContext)).thenReturn(IO(()))

    googleExtensions = mock[GoogleExtensions](RETURNS_SMART_NULLS)
    when(googleExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext]))
      .thenReturn(IO(allUsersGroup))

    when(googleExtensions.onUserCreate(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.getUserStatus(any[SamUser])).thenReturn(IO(true))
    when(googleExtensions.onUserDisable(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onUserEnable(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(()))

    mockTosService = mock[TosService](RETURNS_SMART_NULLS)
    when(mockTosService.getTosComplianceStatus(any[SamUser]))
      .thenAnswer((i: InvocationOnMock) => IO.pure(TermsOfServiceComplianceStatus(i.getArgument[SamUser](0).id, true, true)))

    service = Mockito.spy(new UserService(dirDAO, googleExtensions, Seq(blockedDomain), mockTosService))
  }

  "getUserStatusDiagnostics" should "return UserStatusDiagnostics for a user that exists and is enabled" in {
    val status = service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).unsafeRunSync()
    status shouldBe Some(UserStatusDiagnostics(true, true, true, true, true))
  }

  it should "return UserStatusDiagnostics.enabled and UserStatusDiagnostics.adminEnabled as false if user is disabled" in {
    when(dirDAO.isEnabled(disabledUser.id, samRequestContext)).thenReturn(IO(false))
    val status = service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).unsafeRunSync()
    status.value.enabled shouldBe false
    status.value.adminEnabled shouldBe false
  }

  it should "return UserStatusDiagnostics.inAllUsersGroup as false if user is not in the All_Users group" in {
    when(dirDAO.isGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(false))
    val status = service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).unsafeRunSync()
    status.value.inAllUsersGroup shouldBe false
  }

  it should "return UserStatusDiagnostics.inGoogleProxyGroup as false if user is not a member of their proxy group on Google" in {
    when(googleExtensions.getUserStatus(enabledUser)).thenReturn(IO(false))
    val status = service.getUserStatusDiagnostics(enabledUser.id, samRequestContext).unsafeRunSync()
    status.value.inGoogleProxyGroup shouldBe false
  }

  it should "return UserStatusDiagnostics.tosAccepted as false if user's TOS status is false" in {
    when(mockTosService.getTosComplianceStatus(enabledUser)).thenReturn(IO.pure(TermsOfServiceComplianceStatus(enabledUser.id, false, false)))
    val status = service.getUserStatusDiagnostics(enabledUser.id, samRequestContext).unsafeRunSync()
    status.value.tosAccepted shouldBe false
  }

  it should "return no UserStatusDiagnostics for a user that does not exist" in {
    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(None))
    service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None
  }

  // Tests describing the shared behavior of a user that is being enabled
  def successfullyEnabledUser(user: SamUser): Unit = {
    it should "enable the user in the database" in {
      when(dirDAO.loadUser(user.id, samRequestContext)).thenReturn(IO(Option(user)))
      service.enableUser(defaultUser.id, samRequestContext).unsafeRunSync()
      verify(dirDAO).enableIdentity(user.id, samRequestContext)
    }

    it should "enable the user on google" in {
      when(dirDAO.loadUser(user.id, samRequestContext)).thenReturn(IO(Option(user)))
      service.enableUser(defaultUser.id, samRequestContext).unsafeRunSync()
      verify(googleExtensions).onUserEnable(user, samRequestContext)
    }
  }

  "enableUser for an already enabled user" should behave like successfullyEnabledUser(enabledUser)
  "enableUser for disabled user" should behave like successfullyEnabledUser(disabledUser)

  "enableUser for a non-existent user" should "return None" in {
    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(None))
    val status = service.enableUser(defaultUser.id, samRequestContext).unsafeRunSync()
    status shouldBe None
  }

  // Tests describing the shared behavior of a user that is being disabled
  def successfullyDisabledUser(user: SamUser): Unit = {
    it should "disable the user in the database" in {
      when(dirDAO.loadUser(user.id, samRequestContext)).thenReturn(IO(Option(user)))
      service.disableUser(defaultUser.id, samRequestContext).unsafeRunSync()
      verify(dirDAO).disableIdentity(user.id, samRequestContext)
    }

    it should "disable the user on google" in {
      when(dirDAO.loadUser(user.id, samRequestContext)).thenReturn(IO(Option(user)))
      service.disableUser(defaultUser.id, samRequestContext).unsafeRunSync()
      verify(googleExtensions).onUserDisable(user, samRequestContext)
    }
  }

  "disableUser for an enabled user" should behave like successfullyDisabledUser(enabledUser)
  "disableUser for an already disabled user" should behave like successfullyDisabledUser(disabledUser)

  "disableUser for a non-existent user" should "return None" in {
    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(None))
    val status = service.disableUser(defaultUser.id, samRequestContext).unsafeRunSync()
    status shouldBe None
  }

  "deleteUser" should "remove the user from the All_Users group" in {
    service.deleteUser(defaultUser.id, samRequestContext).unsafeRunSync()
    verify(dirDAO).removeGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)
  }

  it should "delete the user on google" in {
    service.deleteUser(defaultUser.id, samRequestContext).unsafeRunSync()
    verify(googleExtensions).onUserDelete(defaultUser.id, samRequestContext)
  }

  it should "delete the user from the database" in {
    service.deleteUser(defaultUser.id, samRequestContext).unsafeRunSync()
    verify(dirDAO).deleteUser(defaultUser.id, samRequestContext)
  }

  "registerUser" should "create the user in the database if they do not already exist in the database" in {
    when(dirDAO.loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext])).thenReturn(IO(None))
    service.registerUser(disabledUser, samRequestContext).unsafeRunSync()
    verify(dirDAO).createUser(disabledUser, samRequestContext)
  }

  it should "create the user on google if they do not already exist in the database" in {
    when(dirDAO.loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext])).thenReturn(IO(None))
    service.registerUser(disabledUser, samRequestContext).unsafeRunSync()
    verify(googleExtensions).onUserCreate(disabledUser, samRequestContext)
  }

  it should "throw a runtime exception if an exception is thrown when creating a new user record in the database" in {
    when(dirDAO.loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext])).thenReturn(IO(None))
    when(dirDAO.createUser(any[SamUser], any[SamRequestContext])).thenThrow(new RuntimeException("bummer"))
    intercept[RuntimeException] {
      service.registerUser(defaultUser, samRequestContext).unsafeRunSync()
    }
  }
}

object GenEmail {
  val genBadChar = Gen.oneOf("!@#$^&*()=::'\"?/\\`~".toSeq)
  val genEmailUserChar = Gen.frequency((9, Gen.alphaNumChar), (1, Gen.oneOf(Seq('.', '_', '%', '+', '-'))))
  val genEmailUser = Gen.nonEmptyListOf(genEmailUserChar).map(_.mkString)

  val genEmailServerChar = Gen.frequency((9, Gen.alphaNumChar), (1, Gen.const('-')))
  val genEmailServerPart = Gen.nonEmptyListOf(genEmailServerChar).map(_.mkString)
  val genEmailLastPart = Gen.listOfN(2, Gen.alphaChar).map(_.mkString)
}

// This test suite is deprecated.  It is still used and still has valid tests in it, but it should be broken out
// into smaller, more focused suites which should then be added to the `nestedSuites` of `UserServiceSpec`
// This class DOES connect to a real database and its tests should be broken out to individual Spec files
// and rewritten
class OldUserServiceSpec
    extends AnyFlatSpec
    with Matchers
    with TestSupport
    with MockitoSugar
    with PropertyBasedTesting
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalaFutures
    with OptionValues {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(5.seconds))
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  val defaultUser = genWorkbenchUserBoth.sample.get
  val enabledDefaultUserStatus = UserStatusBuilder(defaultUser)
    .withAllUsersGroup(true)
    .withLdap(true)
    .withGoogle(true)
    .withTosAccepted(true)
    .withAdminEnabled(true)
    .build

  lazy val petServiceAccountConfig = TestSupport.appConfig.googleConfig.get.petServiceAccountConfig

  var service: UserService = _
  var googleExtensions: GoogleExtensions = _
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  var tos: TosService = _
  var serviceTosEnabled: UserService = _
  var tosServiceEnabled: TosService = _
  val blockedDomain = "blocked.domain.com"
  val enabledUser: SamUser = defaultUser.copy(enabled = true)
  val disabledUser: SamUser = defaultUser.copy(enabled = false)
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(WorkbenchGroupName("All_Users"), Set(), WorkbenchEmail("all_users@fake.com"))

  before {
    googleExtensions = mock[GoogleExtensions](RETURNS_SMART_NULLS)
    if (databaseEnabled) {
      when(googleExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext]))
        .thenReturn(NoExtensions.getOrCreateAllUsersGroup(dirDAO, samRequestContext))
    }
    when(googleExtensions.onUserCreate(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.getUserStatus(any[SamUser])).thenReturn(IO.pure(true))
    when(googleExtensions.onUserDisable(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onUserEnable(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)
    when(googleExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(()))

    tos = new TosService(dirDAO, TestSupport.tosConfig)
    service = new UserService(dirDAO, googleExtensions, Seq(blockedDomain), tos)
    tosServiceEnabled = new TosService(dirDAO, TestSupport.tosConfig)
    serviceTosEnabled = new UserService(dirDAO, googleExtensions, Seq(blockedDomain), tosServiceEnabled)
  }

  protected def clearDatabase(): Unit =
    TestSupport.truncateAll

  "UserService.genWorkbenchUserId" should "generate unique identifier properly" in {
    val current = 1534253386722L
    val res = UserService.genWorkbenchUserId(current).value
    res.length shouldBe 21
    res.substring(0, current.toString.length) shouldBe "2534253386722"

    // validate when currentMillis doesn't start
    val current2 = 25342533867225L
    val res2 = UserService.genWorkbenchUserId(current2).value
    res2.substring(0, current2.toString.length) shouldBe "25342533867225"
  }

  /** GoogleSubjectId Email no no ---> We've never seen this user before, create a new user
    */
  "UserService registerUser" should "create new user when there's no existing subject for a given googleSubjectId and email" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserGoogle.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(user)
  }

  it should "create new user when there's no existing subject for a given azureB2CId and email" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserAzure.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(user)
  }

  /** GoogleSubjectId Email no yes ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId
    * field for this user.
    */
  it should "update googleSubjectId when there's no existing subject for a given googleSubjectId and but there is one for email" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserGoogle.sample.get
    service.inviteUser(user.email, samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(user.email, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val res = dirDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(id = userId))
  }

  it should "update azureB2CId when there's no existing subject for a given googleSubjectId and but there is one for email" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserAzure.sample.get
    service.inviteUser(user.email, samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(user.email, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val res = dirDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(id = userId))
  }

  /** GoogleSubjectId Email no yes ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId
    * field for this user.
    */
  it should "return BadRequest when there's no existing subject for a given googleSubjectId and but there is one for email, and the returned subject is not a regular user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserGoogle.sample.get.copy(email = genNonPetEmail.sample.get)
    val group = genBasicWorkbenchGroup.sample.get.copy(email = user.email, members = Set.empty)
    dirDAO.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "return BadRequest when there's no existing subject for a given azureB2CId and but there is one for email, and the returned subject is not a regular user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserAzure.sample.get.copy(email = genNonPetEmail.sample.get)
    val group = genBasicWorkbenchGroup.sample.get.copy(email = user.email, members = Set.empty)
    dirDAO.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  /** GoogleSubjectId Email different yes ---> The user's email has already been registered, but the googleSubjectId for the new user is different from what has
    * already been registered. We should throw an exception to prevent the googleSubjectId from being overwritten
    */
  it should "throw an exception when trying to re-register a user with a changed googleSubjectId" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserGoogle.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    assertThrows[WorkbenchException] {
      service.registerUser(user.copy(googleSubjectId = Option(genGoogleSubjectId.sample.get)), samRequestContext).unsafeRunSync()
    }
  }

  it should "throw an exception when trying to re-register a user with a changed azureB2CId" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserAzure.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    assertThrows[WorkbenchException] {
      service.registerUser(user.copy(azureB2CId = Option(genAzureB2CId.sample.get)), samRequestContext).unsafeRunSync()
    }
  }

  /** GoogleSubjectId Email yes skip ---> User exists. Do nothing.
    */
  it should "return conflict when there's an existing subject for a given googleSubjectId" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserGoogle.sample.get
    dirDAO.createUser(user, samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  it should "return conflict when there's an existing subject for a given azureB2CId" in {
    assume(databaseEnabled, databaseEnabledClue)

    val user = genWorkbenchUserAzure.sample.get
    dirDAO.createUser(user, samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  // Test arose out of: https://broadworkbench.atlassian.net/browse/PROD-677
  "Register User" should "ignore the newly-created WorkbenchUserId on the request and use the previously created WorkbenchUserId for a previously-invited user" in {
    assume(databaseEnabled, databaseEnabledClue)

    // Invite a new user
    val emailToInvite = genNonPetEmail.sample.get
    service.inviteUser(emailToInvite, samRequestContext).unsafeRunSync()

    // Lookup the invited user and their ID
    val invitedUserId = dirDAO.loadSubjectFromEmail(emailToInvite, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val invitedUser = dirDAO.loadUser(invitedUserId, samRequestContext).unsafeRunSync().getOrElse(fail("Failed to load invited user after inviting them"))
    invitedUser shouldBe SamUser(invitedUserId, None, emailToInvite, None, false, None)

    // Give them a fake GoogleSubjectId and a new WorkbenchUserId and use that to register them.
    // The real code in org/broadinstitute/dsde/workbench/sam/api/UserRoutes.scala calls
    // org.broadinstitute.dsde.workbench.sam.api.SamUserDirectives.withNewUser which will generate a new
    // WorkbenchUserId for the SamUser on the request.
    val googleSubjectId = Option(GoogleSubjectId("123456789"))
    val newRegisteringUserId = WorkbenchUserId("11111111111111111")
    val registeringUser = SamUser(newRegisteringUserId, googleSubjectId, emailToInvite, None, false, None)
    val registeredUser = service.registerUser(registeringUser, samRequestContext).unsafeRunSync()
    registeredUser.id should {
      equal(invitedUser.id) and
        not equal newRegisteringUserId
    }
  }

  "GetStatus for an invited user" should "return a status that is enabled after the invited user registers" in {
    assume(databaseEnabled, databaseEnabledClue)

    // Invite an email
    val emailToInvite = genNonPetEmail.sample.get
    val invitedUserDetails = service.inviteUser(emailToInvite, samRequestContext).unsafeRunSync()

    // Register a user with that email
    val registeringUser = genWorkbenchUserGoogle.sample.get.copy(email = emailToInvite)
    runAndWait(service.createUser(registeringUser, samRequestContext))

    // Check the status of the invited user
    val invitedUserStatus = service.getUserStatus(invitedUserDetails.userSubjectId, false, samRequestContext).unsafeRunSync()
    val enabledUserStatus = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false, "adminEnabled" -> true)
    invitedUserStatus.value shouldBe UserStatus(invitedUserDetails, enabledUserStatus)
  }

  "invite user and then create user with same email" should "update googleSubjectId for this user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val inviteeEmail = genNonPetEmail.sample.get
    service.inviteUser(inviteeEmail, samRequestContext).unsafeRunSync()
    val invitedUserId = dirDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]

    val userInPostgres = dirDAO.loadUser(invitedUserId, samRequestContext).unsafeRunSync()
    userInPostgres.value should {
      equal(SamUser(invitedUserId, None, inviteeEmail, None, false, None))
    }

    val registeringUser = genWorkbenchUserGoogle.sample.get.copy(email = inviteeEmail)
    runAndWait(service.createUser(registeringUser, samRequestContext))

    val updatedUserInPostgres = dirDAO.loadUser(invitedUserId, samRequestContext).unsafeRunSync()
    updatedUserInPostgres.value shouldBe SamUser(invitedUserId, registeringUser.googleSubjectId, inviteeEmail, None, true, None)
  }

  it should "update azureB2CId for this user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val inviteeEmail = genNonPetEmail.sample.get
    service.inviteUser(inviteeEmail, samRequestContext).unsafeRunSync()
    val invitedUserId = dirDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]

    val userInPostgres = dirDAO.loadUser(invitedUserId, samRequestContext).unsafeRunSync()
    userInPostgres.value should equal(SamUser(invitedUserId, None, inviteeEmail, None, false, None))

    val registeringUser = genWorkbenchUserAzure.sample.get.copy(email = inviteeEmail)
    runAndWait(service.createUser(registeringUser, samRequestContext))

    val updatedUserInPostgres = dirDAO.loadUser(invitedUserId, samRequestContext).unsafeRunSync()
    updatedUserInPostgres.value shouldBe SamUser(invitedUserId, None, inviteeEmail, registeringUser.azureB2CId, true, None)
  }

  "UserService getUserIdInfoFromEmail" should "return the email along with the userSubjectId and googleSubjectId" in {
    assume(databaseEnabled, databaseEnabledClue)

    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).unsafeRunSync()
    newUser shouldBe UserStatus(
      UserStatusDetails(defaultUser.id, defaultUser.email),
      Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false, "adminEnabled" -> true)
    )

    // get user status id info (both subject ids and email)
    val info = service.getUserIdInfoFromEmail(defaultUser.email, samRequestContext).unsafeRunSync()
    info shouldBe Right(Some(UserIdInfo(defaultUser.id, defaultUser.email, Some(defaultUser.googleSubjectId.get))))
  }

  "UserService validateEmailAddress" should "accept valid email addresses" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      parts <- Gen.nonEmptyListOf(genEmailServerPart)
      lastPart <- genEmailLastPart
    } yield WorkbenchEmail(s"$user@${parts.mkString(".")}.$lastPart")
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(service.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isRight)
    }
  }

  it should "reject blocked domain" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createUser(defaultUser.copy(email = WorkbenchEmail(s"user@$blockedDomain")), samRequestContext))
    }.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "reject email addresses missing @" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      parts <- Gen.nonEmptyListOf(genEmailServerPart)
      lastPart <- genEmailLastPart
    } yield WorkbenchEmail(s"$user${parts.mkString(".")}.$lastPart")
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(service.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
    }
  }

  it should "reject email addresses with bad chars" in {
    import GenEmail._

    val genEmail = for {
      badChar <- genBadChar
      position <- Gen.posNum[Int]
      user <- genEmailUser
      parts <- Gen.nonEmptyListOf(genEmailServerPart)
      lastPart <- genEmailLastPart
    } yield {
      val email = s"$user@${parts.mkString(".")}.$lastPart"
      val normalizedPosition = position % email.length
      WorkbenchEmail(email.substring(0, normalizedPosition) + badChar + email(normalizedPosition))
    }
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(service.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
    }
  }

  it should "reject email addresses with bad server" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      lastPart <- genEmailLastPart
    } yield WorkbenchEmail(s"$user@$lastPart")
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(service.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
    }
  }

  it should "reject blocked email domain" in {
    assert(service.validateEmailAddress(WorkbenchEmail("foo@splat.bar.com"), Seq("bar.com")).attempt.unsafeRunSync().isLeft)
    assert(service.validateEmailAddress(WorkbenchEmail("foo@bar.com"), Seq("bar.com")).attempt.unsafeRunSync().isLeft)
  }
}
