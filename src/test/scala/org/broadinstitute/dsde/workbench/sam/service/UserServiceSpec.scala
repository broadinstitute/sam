package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, JndiDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by rtitle on 10/6/17.
  */
class UserServiceSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures {

  override implicit val patienceConfig = PatienceConfig(timeout = scaled(5.seconds))

  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)
  val userInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId(UUID.randomUUID().toString), WorkbenchEmail("user@company.com"), 0)

  lazy val config = ConfigFactory.load()
  lazy val directoryConfig = config.as[DirectoryConfig]("directory")
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val dirDAO = new JndiDirectoryDAO(directoryConfig)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig)

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
    verify(googleExtensions).onUserCreate(defaultUser)

    // check ldap
    dirDAO.loadUser(defaultUserId).futureValue shouldBe Some(defaultUser)
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
  }

  it should "enable/disable user" in {
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
}
