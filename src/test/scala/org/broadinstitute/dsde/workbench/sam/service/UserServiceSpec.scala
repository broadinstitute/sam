package org.broadinstitute.dsde.workbench.sam
package service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.{global => globalEc}
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{arbNonPetEmail => _, _}
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, LdapRegistrationDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by rtitle on 10/6/17.
  */
class UserServiceSpec extends AnyFlatSpec with Matchers with TestSupport with MockitoSugar with PropertyBasedTesting
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures with OptionValues {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(5.seconds))
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  val defaultUser = genWorkbenchUserBoth.sample.get

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
    when(googleExtensions.onUserCreate(any[SamUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.getUserStatus(any[SamUser])).thenReturn(Future.successful(true))
    when(googleExtensions.onUserDisable(any[SamUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserEnable(any[SamUser], any[SamRequestContext])).thenReturn(Future.successful(()))
    when(googleExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])).thenReturn(Future.successful(()))

    tos = new TosService(dirDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    service = new UserService(dirDAO, googleExtensions, registrationDAO, Seq(blockedDomain), tos)
    tosServiceEnabled = new TosService(dirDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig.copy(enabled = true))
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
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    verify(googleExtensions).onUserCreate(defaultUser, samRequestContext)

    dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.copy(enabled = true))
    registrationDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser)
    dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
    registrationDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
    dirDAO.loadGroup(service.cloudExtensions.allUsersGroupName, samRequestContext).unsafeRunSync() shouldBe
      Some(BasicWorkbenchGroup(service.cloudExtensions.allUsersGroupName, Set(defaultUser.id), service.cloudExtensions.getOrCreateAllUsersGroup(dirDAO, samRequestContext).futureValue.email))
  }

  it should "reject blocked domain" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createUser(defaultUser.copy(email = WorkbenchEmail(s"user@$blockedDomain")), samRequestContext))
    }.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "acceptTermsOfService" in {
    serviceTosEnabled.createUser(defaultUser, samRequestContext).futureValue
    val status = serviceTosEnabled.acceptTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()
    status shouldBe Option(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("tosAccepted" -> true, "google" -> true, "ldap" -> true, "allUsersGroup" -> true, "adminEnabled" -> true)))
  }

  it should "get user status" in {
    // user doesn't exist yet
    service.getUserStatus(defaultUser.id, samRequestContext = samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // user should exist now
    val status = service.getUserStatus(defaultUser.id, samRequestContext = samRequestContext).futureValue
    status shouldBe Some(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)))

    val statusNoEnabled = service.getUserStatus(defaultUser.id, true, samRequestContext).futureValue
    statusNoEnabled shouldBe Some(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map.empty))
  }

  it should "get user status info" in {
    // user doesn't exist yet
    service.getUserStatusInfo(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status info (id, email, ldap)
    val info = service.getUserStatusInfo(defaultUser.id, samRequestContext).unsafeRunSync()
    info shouldBe Some(UserStatusInfo(defaultUser.id.value, defaultUser.email.value, true, true))
  }

  it should "get user status diagnostics" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status diagnostics (ldap, usersGroups, googleGroups
    val diagnostics = service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).futureValue
    diagnostics shouldBe Some(UserStatusDiagnostics(true, true, true, None, true))
  }

  it should "enable/disable user" in {
    // user doesn't exist yet
    service.enableUser(defaultUser.id, samRequestContext).futureValue shouldBe None
    service.disableUser(defaultUser.id, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // it should be enabled
    dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
    registrationDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

    // disable the user
    val response = service.disableUser(defaultUser.id, samRequestContext).futureValue
    response shouldBe Some(UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true)))

    // check ldap
    dirDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false
    registrationDAO.isEnabled(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe false
  }

  it should "delete a user" in {
    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // delete the user
    service.deleteUser(defaultUser.id, samRequestContext).futureValue

    // check
    dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None
    registrationDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe None
  }

  it should "generate unique identifier properly" in {
    val current = 1534253386722L
    val res = UserService.genWorkbenchUserId(current).value
    res.length shouldBe(21)
    res.substring(0, current.toString.length) shouldBe("2534253386722")

    // validate when currentMillis doesn't start
    val current2 = 25342533867225L
    val res2 = UserService.genWorkbenchUserId(current2).value
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
    res shouldBe Some(user)
    registrationRes shouldEqual res
  }

  it should "create new user when there's no existing subject for a given azureB2CId and email" in{
    val user = genWorkbenchUserAzure.sample.get
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(user)
  }

  /**
    * GoogleSubjectId    Email
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    */
  it should "update googleSubjectId when there's no existing subject for a given googleSubjectId and but there is one for email" in{
    val user = genWorkbenchUserGoogle.sample.get
    service.inviteUser(user.email, samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(user.email, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val res = dirDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(id = userId))
    registrationRes shouldEqual res
  }

  it should "update azureB2CId when there's no existing subject for a given googleSubjectId and but there is one for email" in{
    val user = genWorkbenchUserAzure.sample.get
    service.inviteUser(user.email, samRequestContext).unsafeRunSync()
    service.registerUser(user, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(user.email, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val res = dirDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(id = userId))
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
    dirDAO.createUser(user, samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  it should "return conflict when there's an existing subject for a given azureB2CId" in{
    val user = genWorkbenchUserAzure.sample.get
    dirDAO.createUser(user, samRequestContext).unsafeRunSync()
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      service.registerUser(user, samRequestContext).unsafeRunSync()
    }
    exception.errorReport shouldEqual ErrorReport(StatusCodes.Conflict, s"user ${user.email} already exists")
  }

  "UserService inviteUser" should "create a new user" in{
    val userEmail = genNonPetEmail.sample.get
    service.inviteUser(userEmail, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(userEmail, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]
    val res = dirDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(userId, samRequestContext).unsafeRunSync()
    res shouldBe Some(SamUser(userId, None, userEmail, None, false, None))
    registrationRes shouldEqual res
  }

  it should "reject blocked domain" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      service.inviteUser(WorkbenchEmail(s"user@$blockedDomain"), samRequestContext).unsafeRunSync()
    }.errorReport.statusCode shouldBe Some(StatusCodes.BadRequest)
  }

  it should "return conflict when there's an existing subject for a given email" in{
    val user = genWorkbenchUserGoogle.sample.get
    dirDAO.createUser(user, samRequestContext).unsafeRunSync()
    val res = intercept[WorkbenchExceptionWithErrorReport] {
      service.inviteUser(user.email, samRequestContext).unsafeRunSync()
    }
    res.errorReport.statusCode shouldBe Option(StatusCodes.Conflict)
  }

  "invite user and then create user with same email" should "update googleSubjectId for this user" in {
    val inviteeEmail = genNonPetEmail.sample.get
    service.inviteUser(inviteeEmail, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]

    val user = genWorkbenchUserGoogle.sample.get.copy(id = userId, email = inviteeEmail)
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val registrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(googleSubjectId = None))
    registrationRes shouldEqual res

    service.createUser(user, samRequestContext).futureValue
    val updated = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    val updatedRegistrationRes = registrationDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    updated shouldBe Some(user.copy(enabled = true))
    updatedRegistrationRes shouldEqual Some(user) // ldap does not know about enabled attribute of user
  }

  it  should "update azureB2CId for this user" in {
    val inviteeEmail = genNonPetEmail.sample.get
    service.inviteUser(inviteeEmail, samRequestContext).unsafeRunSync()
    val userId = dirDAO.loadSubjectFromEmail(inviteeEmail, samRequestContext).unsafeRunSync().value.asInstanceOf[WorkbenchUserId]

    val user = genWorkbenchUserAzure.sample.get.copy(id = userId, email = inviteeEmail)
    val res = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(user.copy(azureB2CId = None))

    service.createUser(user, samRequestContext).futureValue
    val updated = dirDAO.loadUser(user.id, samRequestContext).unsafeRunSync()
    updated shouldBe Some(user.copy(enabled = true))
  }

  "UserService getUserIdInfoFromEmail" should "return the email along with the userSubjectId and googleSubjectId" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUser.id, samRequestContext).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status id info (both subject ids and email)
    val info = service.getUserIdInfoFromEmail(defaultUser.email, samRequestContext).futureValue
    info shouldBe Right(Some(UserIdInfo(defaultUser.id, defaultUser.email, Some(defaultUser.googleSubjectId.get))))
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
