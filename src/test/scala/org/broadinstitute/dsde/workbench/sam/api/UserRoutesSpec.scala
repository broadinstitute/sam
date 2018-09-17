package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, eqWorkbenchExceptionErrorReport}
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService.genRandom
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, NoExtensions, StatusService, UserService}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends UserRoutesSpecHelper {
  "POST /register/user" should "create user" in withDefaultRoutes { samRoutes =>
    val header = TestSupport.genGoogleSubjectIdHeader
    Post("/register/user").withHeaders(header, TestSupport.defaultEmailHeader) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user").withHeaders(header, TestSupport.defaultEmailHeader) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user" should "get the status of an enabled user" in {
    val (user, headers, _, routes) = createTestUser()

    Get("/register/user").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  "POST /register/user" should "retrieve PET owner's user info if a PET account is provided" in {
    val (user, _, samDep, routes) = createTestUser()
    val petEmail = s"pet-${user.id.value}@test.iam.gserviceaccount.com"
    val headers = List(
      RawHeader(emailHeader, petEmail),
      TestSupport.googleSubjectIdHeaderWithId(user.googleSubjectId.get),
      RawHeader(accessTokenHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    //create a PET service account owned by test user
    runAndWait(samDep.directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, null), ServiceAccount(ServiceAccountSubjectId(user.googleSubjectId.get.value), WorkbenchEmail(petEmail), null))))
    Get("/register/user").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  "GET /admin/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/${user.id}").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/email/${user.email}").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "return 404 for an unknown user by email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/XXX${defaultUserEmail}XXX").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 for an group's email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/fc-admins@dev.test.firecloud.org").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/email/$defaultUserEmail").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Put(s"/api/admin/user/${user.id}/disable").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails({user.id}, user.email), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/${user.id}/enable").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails({user.id}, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin to enable or disable a user" in withAdminRoutes { (samRoutes, _) =>
    Put(s"/api/admin/user/$defaultUserId/disable").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }

    Put(s"/api/admin/user/$defaultUserId/enable").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}" should "delete a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/user/${user.id}").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}/petServiceAccount/myproject").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId/petServiceAccount/myproject").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}

trait UserRoutesSpecHelper extends FlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport{
  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")

  val adminUserId = WorkbenchUserId("adminuser")
  val adminGoogleSubjectId = GoogleSubjectId("adminuserGoog")
  val adminUserEmail = WorkbenchEmail("adminuser@new.com")
  val adminHeaders = List(
    RawHeader(accessTokenHeader, ""),
    RawHeader(googleSubjectIdHeader, adminGoogleSubjectId.value),
    RawHeader(emailHeader, adminUserEmail.value),
    RawHeader(expiresInHeader, "1000"),
    TestSupport.genGoogleSubjectIdHeader,
  )

  val petSAUserId = WorkbenchUserId("123")
  val petSAEmail = WorkbenchEmail("pet-newuser@test.iam.gserviceaccount.com")


  def setupAdminsGroup(googleDirectoryDAO: MockGoogleDirectoryDAO): Future[WorkbenchEmail] = {
    val adminGroupEmail = WorkbenchEmail("fc-admins@dev.test.firecloud.org")
    for {
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName("fc-admins"), adminGroupEmail)
      _ <- googleDirectoryDAO.addMemberToGroup(adminGroupEmail, WorkbenchEmail(adminUserEmail.value))
    } yield adminGroupEmail
  }

  def setUpAdminTest(): (WorkbenchUser, SamRoutes) = {
    val googDirectoryDAO = new MockGoogleDirectoryDAO()
    val adminGroupEmail = runAndWait(setupAdminsGroup(googDirectoryDAO))
    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }
    val (user, _, _, routes) = createTestUser(cloudExtensions = Some(cloudExtensions), googleDirectoryDAO = Some(googDirectoryDAO))
    Post("/register/user/v1/").withHeaders(adminHeaders) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
    (user, routes)
  }

  def createTestUser(googSubjectId: Option[GoogleSubjectId] = None, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None): (WorkbenchUser, List[RawHeader], SamDependencies, SamRoutes) = {
    val googleSubjectId = googSubjectId.map(_.value).getOrElse(genRandom(System.currentTimeMillis()))
    val googleSubjectheader = RawHeader(googleSubjectIdHeader, googleSubjectId)
    val emHeader = RawHeader(emailHeader, defaultUserEmail.value)

    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO)
    val routes = genSamRoutes(samDependencies)

    // create a user
    val user = Post("/register/user/v1/").withHeaders(googleSubjectheader, emHeader) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)

      WorkbenchUser(res.userInfo.userSubjectId, Some(GoogleSubjectId(googleSubjectId)), res.userInfo.userEmail)
    }
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      TestSupport.googleSubjectIdHeaderWithId(user.googleSubjectId.get),
      RawHeader(accessTokenHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    (user, headers, samDependencies, routes)
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()

    val adminGroupEmail = runAndWait(setupAdminsGroup(googleDirectoryDAO))

    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googleDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, cloudExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, cloudExtensions)
    val adminRoutes = new TestSamRoutes(null, new UserService(directoryDAO, cloudExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), adminUserId, adminUserEmail, 0), directoryDAO, cloudExtensions)
    testCode(samRoutes, adminRoutes)
  }

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO)
    testCode(samRoutes)
  }
}