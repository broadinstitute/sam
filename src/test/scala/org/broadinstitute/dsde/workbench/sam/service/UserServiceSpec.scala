package org.broadinstitute.dsde.workbench.sam
package service

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, PetServiceAccountConfig, SchemaLockConfig}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, LdapDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import TestSupport.eqWorkbenchExceptionErrorReport
import cats.kernel.Eq
import org.broadinstitute.dsde.workbench.sam.api.{CreateWorkbenchUser, InviteUser}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by rtitle on 10/6/17.
  */
class UserServiceSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(5.seconds))

  val defaultUserId = genWorkbenchUserId(System.currentTimeMillis())
  val defaultGoogleSubjectId = GoogleSubjectId(defaultUserId.value)
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val defaultUser = CreateWorkbenchUser(defaultUserId, defaultGoogleSubjectId, defaultUserEmail)
  val userInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId(UUID.randomUUID().toString), WorkbenchEmail("user@company.com"), 0)

  lazy val config = ConfigFactory.load()
  lazy val directoryConfig = config.as[DirectoryConfig]("directory")
  lazy val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val dirURI = new URI(directoryConfig.directoryUrl)
  lazy val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  var service: UserService = _
  var googleExtensions: GoogleExtensions = _

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())

    googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.allUsersGroupName).thenReturn(NoExtensions.allUsersGroupName)
    when(googleExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO])(any[ExecutionContext])).thenReturn(NoExtensions.getOrCreateAllUsersGroup(dirDAO))
    when(googleExtensions.onUserCreate(any[WorkbenchUser])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserDelete(any[WorkbenchUserId])).thenReturn(Future.successful(()))
    when(googleExtensions.getUserStatus(any[WorkbenchUser])).thenReturn(Future.successful(true))
    when(googleExtensions.onUserDisable(any[WorkbenchUser])).thenReturn(Future.successful(()))
    when(googleExtensions.onUserEnable(any[WorkbenchUser])).thenReturn(Future.successful(()))

    service = new UserService(dirDAO, googleExtensions)
  }

  "UserService" should "create a user" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    verify(googleExtensions).onUserCreate(WorkbenchUser(defaultUser.id, Some(defaultUser.googleSubjectId), defaultUser.email))

    // check ldap
    dirDAO.loadUser(defaultUserId).futureValue shouldBe Some(WorkbenchUser(defaultUser.id, Some(defaultUser.googleSubjectId), defaultUser.email))
    dirDAO.isEnabled(defaultUserId).futureValue shouldBe true
    dirDAO.loadGroup(service.cloudExtensions.allUsersGroupName).futureValue shouldBe
      Some(BasicWorkbenchGroup(service.cloudExtensions.allUsersGroupName, Set(defaultUserId), service.cloudExtensions.getOrCreateAllUsersGroup(dirDAO).futureValue.email))
  }

  it should "get user status" in {
    // user doesn't exist yet
    service.getUserStatus(defaultUserId).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // user should exist now
    val status = service.getUserStatus(defaultUserId).futureValue
    status shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)))

    val statusNoEnabled = service.getUserStatus(defaultUserId, true).futureValue
    statusNoEnabled shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map.empty))
  }

  it should "get user status info" in {
    // user doesn't exist yet
    service.getUserStatusInfo(defaultUserId).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status info (id, email, ldap)
    val info = service.getUserStatusInfo(defaultUserId).futureValue
    info shouldBe Some(UserStatusInfo(defaultUserId.value, defaultUserEmail.value, true))
  }

  it should "get user status diagnostics" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUserId).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status diagnostics (ldap, usersGroups, googleGroups
    val diagnostics = service.getUserStatusDiagnostics(defaultUserId).futureValue
    diagnostics shouldBe Some(UserStatusDiagnostics(true, true, true))
  }

  it should "enable/disable user" in {
    // user doesn't exist yet
    service.enableUser(defaultUserId, userInfo).futureValue shouldBe None
    service.disableUser(defaultUserId, userInfo).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // it should be enabled
    dirDAO.isEnabled(defaultUserId).futureValue shouldBe true

    // disable the user
    val response = service.disableUser(defaultUserId, userInfo).futureValue
    response shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true)))

    // check ldap
    dirDAO.isEnabled(defaultUserId).futureValue shouldBe false
  }

  it should "delete a user" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // delete the user
    service.deleteUser(defaultUserId, userInfo).futureValue

    // check ldap
    dirDAO.loadUser(defaultUserId).futureValue shouldBe None
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
    val user = genCreateWorkbenchUser.sample.get
    service.registerUser(user).futureValue
    val res = dirDAO.loadUser(user.id).futureValue
    res shouldBe Some(WorkbenchUser(user.id, Some(user.googleSubjectId), user.email))
  }

  /**
    * GoogleSubjectId    Email
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    */
  it should "update googleSubjectId when there's no existing subject for a given googleSubjectId and but there is one for email" in{
    val user = genCreateWorkbenchUser.sample.get
    dirDAO.createUser(WorkbenchUser(user.id, None, user.email)).futureValue
    service.registerUser(user).futureValue
    val res = dirDAO.loadUser(user.id).futureValue
    res shouldBe Some(WorkbenchUser(user.id, Some(user.googleSubjectId), user.email))
  }

  /**
    * GoogleSubjectId    Email
    *      no             yes      ---> Someone invited this user previous and we have a record for this user already. We just need to update GoogleSubjetId field for this user.
    */
  it should "return BadRequest when there's no existing subject for a given googleSubjectId and but there is one for email, and the returned subject is not a regular user" in{
    val user = genCreateWorkbenchUser.sample.get.copy(email = genNonPetEmail.sample.get)
    val group = genBasicWorkbenchGroup.sample.get.copy(email = user.email)
    dirDAO.createGroup(group).futureValue
    val res = service.registerUser(user).failed.futureValue.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"$user is not a regular user. Please use a different endpoint"))) shouldBe true
  }

  /**
    * GoogleSubjectId    Email
    *      yes            skip    ---> User exists. Do nothing.
    */
  it should "return conflict when there's an existing subject for a given googleSubjectId" in{
    val user = genCreateWorkbenchUser.sample.get
    dirDAO.createUser(WorkbenchUser(user.id, Some(user.googleSubjectId), user.email)).futureValue
    val res = service.registerUser(user).failed.futureValue.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user} already exists"))) shouldBe true
  }

  "UserService inviteUser" should "create a new user" in{
    val user = genInviteUser.sample.get
    service.inviteUser(user).futureValue
    val res = dirDAO.loadUser(user.inviteeId).futureValue
    res shouldBe Some(WorkbenchUser(user.inviteeId, None, user.inviteeEmail))
  }

  it should "return conflict when there's an existing subject for a given userId" in{
    val user = genInviteUser.sample.get
    val email = genNonPetEmail.sample.get
    dirDAO.createUser(WorkbenchUser(user.inviteeId, None, email)).futureValue
    val res = service.inviteUser(user).failed.futureValue.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.inviteeId} already exists"))) shouldBe true
  }

  it should "return conflict when there's an existing subject for a given email" in{
    val user = genInviteUser.sample.get
    val userId = genWorkbenchUserId(System.currentTimeMillis())
    dirDAO.createUser(WorkbenchUser(userId, None, user.inviteeEmail)).futureValue
    val res = service.inviteUser(user).failed.futureValue.asInstanceOf[WorkbenchExceptionWithErrorReport]
    Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"email ${user.inviteeEmail} already exists"))) shouldBe true
  }

  "invite user and then create user with same email" should "update googleSubjectId for this user" in {
    val user = genCreateWorkbenchUser.sample.get
    service.inviteUser(InviteUser(user.id, user.email)).futureValue
    val res = dirDAO.loadUser(user.id).futureValue
    res shouldBe Some(WorkbenchUser(user.id, None, user.email))

    service.createUser(user).futureValue
    val updated = dirDAO.loadUser(user.id).futureValue
    updated shouldBe Some(WorkbenchUser(user.id, Some(user.googleSubjectId), user.email))
  }

  "UserService getUserIdInfoFromEmail" should "return the email along with the userSubjectId and googleSubjectId" in {
    // user doesn't exist yet
    service.getUserStatusDiagnostics(defaultUserId).futureValue shouldBe None

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // get user status id info (both subject ids and email)
    val info = service.getUserIdInfoFromEmail(defaultUserEmail).futureValue
    info shouldBe Some(UserIdInfo(defaultUserId, defaultUserEmail, Some(defaultGoogleSubjectId)))
  }
}
