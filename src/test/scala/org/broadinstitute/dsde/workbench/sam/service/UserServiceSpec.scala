package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, UserInfo, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService.allUsersGroupName
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Try}

/**
  * Created by rtitle on 10/6/17.
  */
class UserServiceSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures {
  override implicit val patienceConfig = PatienceConfig(timeout = scaled(5.seconds))

  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchUserEmail("newuser@new.com")
  val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)
  val userInfo = UserInfo("token", WorkbenchUserId(UUID.randomUUID().toString), WorkbenchUserEmail("user@company.com"), 0)

  lazy val config = ConfigFactory.load()
  lazy val directoryConfig = config.as[DirectoryConfig]("directory")
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val dirDAO = new JndiDirectoryDAO(directoryConfig)
  lazy val schemaDao = new JndiSchemaDAO(directoryConfig)

  var service: UserService = _

  override protected def beforeAll(): Unit = {
    runAndWait(schemaDao.init())
  }

  override protected def afterAll(): Unit = {
    runAndWait(schemaDao.clearDatabase())
  }

  before {
    service = new UserService(dirDAO, new MockGoogleDirectoryDAO(), new MockGoogleIamDAO(), "dev.test.firecloud.org", petServiceAccountConfig)
  }

  after {
    // clean up
    dirDAO.removePetServiceAccountFromUser(defaultUserId).futureValue
    dirDAO.removeGroupMember(allUsersGroupName, defaultUserId).recover { case _ => () }.futureValue
    dirDAO.disableIdentity(defaultUserId).futureValue
    dirDAO.deleteUser(defaultUserId).futureValue
    dirDAO.deleteGroup(UserService.allUsersGroupName).futureValue
  }

  "UserService" should "create a user" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // check ldap
    dirDAO.loadUser(defaultUserId).futureValue shouldBe Some(defaultUser)
    dirDAO.isEnabled(defaultUserId).futureValue shouldBe true
    dirDAO.loadGroup(UserService.allUsersGroupName).futureValue shouldBe
      Some(BasicWorkbenchGroup(UserService.allUsersGroupName, Set(defaultUserId), WorkbenchGroupEmail(service.toGoogleGroupName(UserService.allUsersGroupName.value))))

    // check google
    val mockGoogleDirectoryDAO = service.googleDirectoryDAO.asInstanceOf[MockGoogleDirectoryDAO]
    val groupEmail = WorkbenchGroupEmail(service.toProxyFromUser(defaultUserId.value))
    val allUsersGroupEmail = WorkbenchGroupEmail(service.toGoogleGroupName(UserService.allUsersGroupName.value))
    mockGoogleDirectoryDAO.groups should contain key (groupEmail)
    mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set(defaultUserEmail)
    mockGoogleDirectoryDAO.groups should contain key (allUsersGroupEmail)
    mockGoogleDirectoryDAO.groups(allUsersGroupEmail) shouldBe Set(WorkbenchUserEmail(service.toProxyFromUser(defaultUserId.value)))
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
    response shouldBe Some(UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> false)))

    // check ldap
    dirDAO.isEnabled(defaultUserId).futureValue shouldBe false

    // check google
    val mockGoogleDirectoryDAO = service.googleDirectoryDAO.asInstanceOf[MockGoogleDirectoryDAO]
    val groupEmail = WorkbenchGroupEmail(service.toProxyFromUser(defaultUserId.value))
    mockGoogleDirectoryDAO.groups should contain key (groupEmail)
    mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set()
  }

  it should "delete a user" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // delete the user
    service.deleteUser(defaultUserId, userInfo).futureValue

    // check ldap
    dirDAO.loadUser(defaultUserId).futureValue shouldBe None

    // check google
    val mockGoogleDirectoryDAO = service.googleDirectoryDAO.asInstanceOf[MockGoogleDirectoryDAO]
    val groupEmail = WorkbenchGroupEmail(service.toProxyFromUser(defaultUserId.value))
    mockGoogleDirectoryDAO.groups should not contain key (groupEmail)
  }

  it should "get a pet service account for a user" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val emailResponse = service.createUserPetServiceAccount(defaultUser).futureValue

    emailResponse.value should endWith ("@my-pet-project.iam.gserviceaccount.com")

    // verify ldap
    dirDAO.getPetServiceAccountForUser(defaultUserId).futureValue shouldBe Some(emailResponse)

    val ldapPetOpt = dirDAO.loadSubjectFromEmail(emailResponse.value).flatMap {
      case Some(subject: WorkbenchUserServiceAccountSubjectId) => dirDAO.loadPetServiceAccount(subject)
      case _ => fail(s"could not load pet LDAP entry from $emailResponse")
    }.futureValue

    ldapPetOpt shouldBe 'defined
    val Some(ldapPet) = ldapPetOpt
    ldapPet.email shouldBe WorkbenchUserServiceAccountEmail(emailResponse.value)
    ldapPet.displayName shouldBe WorkbenchUserServiceAccountDisplayName("")
    // MockGoogleIamDAO generates the subject ID as a random Long
    Try(ldapPet.subjectId.value.toLong) shouldBe a [Success[_]]

    // verify google
    val mockGoogleIamDAO = service.googleIamDAO.asInstanceOf[MockGoogleIamDAO]
    val mockGoogleDirectoryDAO = service.googleDirectoryDAO.asInstanceOf[MockGoogleDirectoryDAO]
    val groupEmail = WorkbenchGroupEmail(service.toProxyFromUser(defaultUserId.value))
    mockGoogleIamDAO.serviceAccounts should contain key (emailResponse)
    mockGoogleDirectoryDAO.groups should contain key (groupEmail)
    mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set(defaultUserEmail, emailResponse)

    // create one again, it should work
    val petSaResponse2 = service.createUserPetServiceAccount(defaultUser).futureValue
    petSaResponse2 shouldBe emailResponse

    dirDAO.disableIdentity(ldapPet.subjectId).futureValue
    dirDAO.deletePetServiceAccount(ldapPet.subjectId).futureValue
  }

  it should "delete a pet service account" in {
    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val emailResponse = service.createUserPetServiceAccount(defaultUser).futureValue
    emailResponse.value should endWith ("@my-pet-project.iam.gserviceaccount.com")

    // verify ldap
    dirDAO.getPetServiceAccountForUser(defaultUserId).futureValue shouldBe Some(emailResponse)

    // verify google
    val mockGoogleIamDAO = service.googleIamDAO.asInstanceOf[MockGoogleIamDAO]
    val mockGoogleDirectoryDAO = service.googleDirectoryDAO.asInstanceOf[MockGoogleDirectoryDAO]
    val groupEmail = WorkbenchGroupEmail(service.toProxyFromUser(defaultUserId.value))
    mockGoogleIamDAO.serviceAccounts should contain key (emailResponse)
    mockGoogleDirectoryDAO.groups should contain key (groupEmail)
    mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set(defaultUserEmail, emailResponse)

    // delete the pet service account
    service.deleteUserPetServiceAccount(newUser.userInfo.userSubjectId).futureValue

    // the user should still exist in LDAP
    dirDAO.loadUser(defaultUserId).futureValue shouldBe Some(defaultUser)

    // the pet should not exist in LDAP
    dirDAO.getPetServiceAccountForUser(defaultUserId).futureValue shouldBe None

    // the pet should not exist in Google
    mockGoogleIamDAO.serviceAccounts should not contain key (emailResponse)
  }

}
