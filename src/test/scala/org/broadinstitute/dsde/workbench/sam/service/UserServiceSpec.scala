package org.broadinstitute.dsde.workbench.sam
package service

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.kernel.Eq
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{arbNonPetEmail => _, _}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{eqWorkbenchExceptionErrorReport, googleServicesConfig}
import org.broadinstitute.dsde.workbench.sam.api.InviteUser
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatestplus.mockito.MockitoSugar

import java.net.URI
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by rtitle on 10/6/17.
  */
class UserServiceSpec extends AnyFlatSpec with Matchers with TestSupport with MockitoSugar with PropertyBasedTesting
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(5.seconds))
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  val defaultUserId = genWorkbenchUserId(System.currentTimeMillis())
  val defaultGoogleSubjectId = GoogleSubjectId(defaultUserId.value)
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val defaultUser = WorkbenchUser(defaultUserId, Option(defaultGoogleSubjectId), defaultUserEmail, None)
  val userInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId(UUID.randomUUID().toString), WorkbenchEmail("user@company.com"), 0)

  lazy val directoryConfig = TestSupport.appConfig.directoryConfig
  lazy val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  lazy val petServiceAccountConfig = TestSupport.appConfig.googleConfig.get.petServiceAccountConfig
  lazy val dirURI = new URI(directoryConfig.directoryUrl)
  lazy val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  lazy val registrationDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  var service: UserService = _
  var tos: TosService = _
  var serviceTosEnabled: UserService = _
  var tosServiceEnabled: TosService = _
  var googleExtensions: GoogleExtensions = _
  val blockedDomain = "blocked.domain.com"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    clearDatabase()

    googleExtensions = mock[GoogleExtensions](RETURNS_SMART_NULLS)
    when(googleExtensions.allUsersGroupName).thenReturn(NoExtensions.allUsersGroupName)
    when(googleExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext])).thenReturn(NoExtensions.getOrCreateAllUsersGroup(dirDAO, samRequestContext))
    when(googleExtensions.onUserCreate(any[WorkbenchUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.getUserStatus(any[WorkbenchUser])).thenReturn(Future.successful(true))
    when(googleExtensions.onUserDisable(any[WorkbenchUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserEnable(any[WorkbenchUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(()))

    tos = new TosService(dirDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    service = new UserService(dirDAO, googleExtensions, registrationDAO, Seq(blockedDomain), tos)

    tosServiceEnabled = new TosService(dirDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig.copy(enabled = true))
    serviceTosEnabled = new UserService(dirDAO, googleExtensions, registrationDAO, Seq(blockedDomain), tosServiceEnabled)
  }

  protected def clearDatabase(): Unit = {
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    TestSupport.truncateAll
  }

  "UserService" should "create a user" in {
    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    verify(googleExtensions).onUserCreate(WorkbenchUser(defaultUser.id,  defaultUser.googleSubjectId, defaultUser.email, defaultUser.azureB2CId), samRequestContext)

    // check ldap
    dirDAO.loadUser(defaultUserId, samRequestContext).unsafeRunSync() shouldBe Some(WorkbenchUser(defaultUser.id,  defaultUser.googleSubjectId, defaultUser.email, defaultUser.azureB2CId))
    registrationDAO.loadUser(defaultUserId, samRequestContext).unsafeRunSync() shouldBe Some(WorkbenchUser(defaultUser.id,  defaultUser.googleSubjectId, defaultUser.email, defaultUser.azureB2CId))
    dirDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe true
    registrationDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe true
    dirDAO.loadGroup(service.cloudExtensions.allUsersGroupName, samRequestContext).unsafeRunSync() shouldBe
      Some(BasicWorkbenchGroup(service.cloudExtensions.allUsersGroupName, Set(defaultUserId), service.cloudExtensions.getOrCreateAllUsersGroup(dirDAO, samRequestContext).futureValue.email))
  }

  it should "reject blocked domain" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createUser(defaultUser.copy(email = WorkbenchEmail(s"user@$blockedDomain")), samRequestContext))
    }.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "create add a user to ToS group" in {
    tosServiceEnabled.createNewGroupIfNeeded().unsafeRunSync()
    serviceTosEnabled.createUser(defaultUser, samRequestContext).futureValue
    val userGroups = dirDAO.listUsersGroups(defaultUserId, samRequestContext).unsafeRunSync()
    userGroups shouldNot contain (WorkbenchGroupName(tos.getGroupName(TestSupport.tosConfig.version)))
    userGroups should have size 1

    serviceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()
    val newUserGroups = dirDAO.listUsersGroups(defaultUserId, samRequestContext).unsafeRunSync()
    newUserGroups should contain (WorkbenchGroupName(tos.getGroupName(TestSupport.tosConfig.version)))
    newUserGroups should have size 2
  }

  it should "not add user to ToS when tos is not enabled" in {
    service.createUser(defaultUser, samRequestContext).futureValue
    val userGroups = dirDAO.listUsersGroups(defaultUserId, samRequestContext).unsafeRunSync()
    userGroups should have size 1
  }

  it should "get user status" in {
    // user doesn't exist yet
    service.getUserStatus(defaultUserId, samRequestContext = samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // user should exist now
    val status = service.getUserStatus(defaultUserId, samRequestContext = samRequestContext).futureValue
    status shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)))

    val statusNoEnabled = service.getUserStatus(defaultUserId, true, samRequestContext).futureValue
    statusNoEnabled shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map.empty))
  }

  it should "get user status info" in {
    // user doesn't exist yet
    service.getUserStatusInfo(defaultUserId, samRequestContext).unsafeRunSync() shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status info (id, email, ldap)
    val info = service.getUserStatusInfo(defaultUserId, samRequestContext).unsafeRunSync()
    info shouldBe Some(UserStatusInfo(defaultUserId.value, defaultUserEmail.value, true))
  }

  it should "get user status diagnostics" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUserId, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status diagnostics (ldap, usersGroups, googleGroups
    val diagnostics = service.getUserStatusDiagnostics(defaultUserId, samRequestContext).futureValue
    diagnostics shouldBe Some(UserStatusDiagnostics(true, true, true, None))
  }

  it should "enable/disable user" in {
    // user doesn't exist yet
    service.enableUser(defaultUserId, userInfo, samRequestContext).futureValue shouldBe None
    service.disableUser(defaultUserId, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // it should be enabled
    dirDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe true
    registrationDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe true

    // disable the user
    val response = service.disableUser(defaultUserId, samRequestContext).futureValue
    response shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true)))

    // check ldap
    dirDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe false
    registrationDAO.isEnabled(defaultUserId, samRequestContext).unsafeRunSync() shouldBe false
  }

  it should "delete a user" in {
    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // delete the user
    service.deleteUser(defaultUserId, userInfo, samRequestContext).futureValue

    // check
    dirDAO.loadUser(defaultUserId, samRequestContext).unsafeRunSync() shouldBe None
    registrationDAO.loadUser(defaultUserId, samRequestContext).unsafeRunSync() shouldBe None
  }

  it should "accept the tos" in {
    tosServiceEnabled.createNewGroupIfNeeded().unsafeRunSync()

    // create a user
    val newUser = serviceTosEnabled.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false))

    serviceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    val status = serviceTosEnabled.getUserStatus(defaultUserId, samRequestContext = samRequestContext).futureValue
    status shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true)))
  }

  it should "not accept the tos for users who do not exist" in {
    tosServiceEnabled.createNewGroupIfNeeded().unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      serviceTosEnabled.acceptTermsOfService(genWorkbenchUserId(System.currentTimeMillis()), samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
  }

  it should "not accept the tos for users when the terms of service is not enabled" in {
    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    service.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

    val status = service.getUserStatus(defaultUserId, samRequestContext = samRequestContext).futureValue
    status shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)))

  }

  it should "generate unique identifier properly" in {
    val current = 1534253386722L
    val res = genWorkbenchUserId(current).value
    res.length shouldBe(21)
    res.substring(0, current.toString.length) shouldBe("2534253386722")

    // validate when currentMillis doesn't start
    val current2 = 25342533867225L
    val res2 = genWorkbenchUserId(current2).value
    res2.substring(0, current2.toString.length) shouldBe("25342533867225")
  }

  /**
    * GoogleSubjectId    Email
    *    no              no      ---> We've never seen this user before, create a new user
    */
  "UserService registerUser" should "create new user when there's no existing subject for a given googleSubjectId and email" in{
    val user = genWorkbenchUserGoogle.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId))
    registrationRes shouldEqual res
  }

  it should "create new user when there's no existing subject for a given azureB2CId and email" in{
    val user = genWorkbenchUserAzure.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId))
  }

  /**
    * GoogleSubjectId    Email
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    */
  it should "update googleSubjectId when there's no existing subject for a given googleSubjectId and but there is one for email" in{
    val user = genWorkbenchUserGoogle.sample.get
    service.inviteUser(InviteUser(user.id, user.email), samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id, user.googleSubjectId, user.email, user.azureB2CId))
    registrationRes shouldEqual res
  }

  it should "update azureB2CId when there's no existing subject for a given googleSubjectId and but there is one for email" in{
    val user = genWorkbenchUserAzure.sample.get
    service.inviteUser(InviteUser(user.id, user.email), samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id, user.googleSubjectId, user.email, user.azureB2CId))
  }

  /**
    * GoogleSubjectId    Email
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    */
  it should "return BadRequest when there's no existing subject for a given googleSubjectId and but there is one for email, and the returned subject is not a regular user" in{
    val user = genWorkbenchUserGoogle.sample.get.copy(email = genNonPetEmail.sample.get)
    val group = genBasicWorkbenchGroup.sample.get.copy(email = user.email, members = Set.empty)
    dirDAO.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "return BadRequest when there's no existing subject for a given azureB2CId and but there is one for email, and the returned subject is not a regular user" in{
    val user = genWorkbenchUserAzure.sample.get.copy(email = genNonPetEmail.sample.get)
    val group = genBasicWorkbenchGroup.sample.get.copy(email = user.email, members = Set.empty)
    dirDAO.createGroup(group, samRequestContext = samRequestContext).unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  /**
    * GoogleSubjectId    Email
    *    different        yes     ---> The user's email has already been registered, but the googleSubjectId for the new user is different from what has already been registered. We should throw an exception to prevent the googleSubjectId from being overwritten
    */
  it should "throw an exception when trying to re-register a user with a changed googleSubjectId" in {
    val user = genWorkbenchUserGoogle.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    assertThrows[WorkbenchException] {
      service.registerUser(user.copy(googleSubjectId = Option(genGoogleSubjectId.sample.get)), samRequestContext).unsafeRunSync()
    }
  }

  it should "throw an exception when trying to re-register a user with a changed azureB2CId" in {
    val user = genWorkbenchUserAzure.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    assertThrows[WorkbenchException] {
      service.registerUser(user.copy(azureB2CId = Option(genAzureB2CId.sample.get)), samRequestContext).unsafeRunSync()
    }
  }

  /**
    * GoogleSubjectId    Email
    *      yes            skip    ---> User exists. Do nothing.
    */
  it should "return conflict when there's an existing subject for a given googleSubjectId" in{
    val user = genWorkbenchUserGoogle.sample.get
    dirDAO.createUser(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId), samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  it should "return conflict when there's an existing subject for a given azureB2CId" in{
    val user = genWorkbenchUserAzure.sample.get
    dirDAO.createUser(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId), samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  "UserService inviteUser" should "create a new user" in{
    val user = genInviteUser.sample.get
    service.inviteUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.inviteeId, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(user.inviteeId, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.inviteeId, None, user.inviteeEmail, None))
    registrationRes shouldEqual res
  }

  it should "reject blocked domain" in {
    val user = genInviteUser.sample.get
    intercept[WorkbenchExceptionWithErrorReport] {
      service.inviteUser(user.copy(inviteeEmail = WorkbenchEmail(s"user@$blockedDomain")), samRequestContext).unsafeRunSync()
    }.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "return conflict when there's an existing subject for a given userId" in{
    val user = genInviteUser.sample.get
    val email = genNonPetEmail.sample.get
    dirDAO.createUser(WorkbenchUser(user.inviteeId, None, email, None), samRequestContext).unsafeRunSync()
    val res = service.inviteUser(user, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.inviteeId} already exists"))) shouldBe true
  }

  it should "return conflict when there's an existing subject for a given email" in{
    val user = genInviteUser.sample.get
    val userId = genWorkbenchUserId(System.currentTimeMillis())
    dirDAO.createUser(WorkbenchUser(userId, None, user.inviteeEmail, None), samRequestContext).unsafeRunSync()
    val res = service.inviteUser(user, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${user.inviteeEmail} already exists"))) shouldBe true
  }

  "invite user and then create user with same email" should "update googleSubjectId for this user" in {
    val user = genWorkbenchUserGoogle.sample.get
    service.inviteUser(InviteUser(user.id, user.email), samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id, None, user.email, None))
    registrationRes shouldEqual res

    service.createUser(user, samRequestContext).futureValue
    val updated = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val updatedRegistrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    updated shouldBe Some(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId))
    updatedRegistrationRes shouldEqual updated
  }

  it  should "update azureB2CId for this user" in {
    val user = genWorkbenchUserAzure.sample.get
    service.inviteUser(InviteUser(user.id, user.email), samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(WorkbenchUser(user.id, None, user.email, None))

    service.createUser(user, samRequestContext).futureValue
    val updated = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    updated shouldBe Some(WorkbenchUser(user.id,  user.googleSubjectId, user.email, user.azureB2CId))
  }

  "UserService getUserIdInfoFromEmail" should "return the email along with the userSubjectId and googleSubjectId" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUserId, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status id info (both subject ids and email)
    val info = service.getUserIdInfoFromEmail(defaultUserEmail, samRequestContext).futureValue
    info shouldBe Right(Some(UserIdInfo(defaultUserId, defaultUserEmail, Some(defaultGoogleSubjectId))))
  }

  "UserService validateEmailAddress" should "accept valid email addresses" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      parts <- Gen.nonEmptyListOf(genEmailServerPart)
      lastPart <- genEmailLastPart
    } yield {
      WorkbenchEmail(s"$user@${parts.mkString(".")}.$lastPart")
    }
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(UserService.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isRight)
    }
  }

  it should "reject email addresses missing @" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      parts <- Gen.nonEmptyListOf(genEmailServerPart)
      lastPart <- genEmailLastPart
    } yield {
      WorkbenchEmail(s"$user${parts.mkString(".")}.$lastPart")
    }
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(UserService.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
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
      assert(UserService.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
    }
  }

  it should "reject email addresses with bad server" in {
    import GenEmail._

    val genEmail = for {
      user <- genEmailUser
      lastPart <- genEmailLastPart
    } yield {
      WorkbenchEmail(s"$user@$lastPart")
    }
    implicit val arbEmail: Arbitrary[WorkbenchEmail] = Arbitrary(genEmail)

    forAll { email: WorkbenchEmail =>
      assert(UserService.validateEmailAddress(email, Seq.empty).attempt.unsafeRunSync().isLeft)
    }
  }

  it should "reject blocked email domain" in {
    assert(UserService.validateEmailAddress(WorkbenchEmail("foo@splat.bar.com"), Seq("bar.com")).attempt.unsafeRunSync().isLeft)
    assert(UserService.validateEmailAddress(WorkbenchEmail("foo@bar.com"), Seq("bar.com")).attempt.unsafeRunSync().isLeft)
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
