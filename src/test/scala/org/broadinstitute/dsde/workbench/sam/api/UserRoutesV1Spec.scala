package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator.genInviteUser
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, UserService}

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesV1Spec extends UserRoutesSpecHelper{
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v1/" should "create user" in withDefaultRoutes{samRoutes =>
    val header = List(TestSupport.genGoogleSubjectIdHeader, TestSupport.defaultEmailHeader)
    Post("/register/user/v1/").withHeaders(header) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v1/").withHeaders(header) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "POST /api/users/v1/invite/{invitee's email}" should "create user" in{
    val invitee = genInviteUser.sample.get

    val (user, headers, _, routes) = createTestUser() //create a valid user that can invite someone
    Post(s"/api/users/v1/invite/${invitee.inviteeEmail}").withHeaders(headers) ~> routes.route ~> check{
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatusDetails]
      res.userEmail shouldBe invitee.inviteeEmail
    }
  }

  "GET /register/user/v1/" should "get the status of an enabled user" in {
    val (user, headers, samDep, routes) = createTestUser()
    val googleSubjectIdHeader = TestSupport.googleSubjectIdHeaderWithId(user.googleSubjectId.get)

    Get("/register/user/v1/").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1?userDetailsOnly=true").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map.empty)
    }
  }

  "POST /register/user/v1/" should "retrieve PET owner's user info if a PET account is provided" in {
    val (user, _, samDep, routes) = createTestUser()
    val petEmail = s"pet-${user.id.value}@test.iam.gserviceaccount.com"
    val headers = List(
      RawHeader(emailHeader, petEmail),
      TestSupport.googleSubjectIdHeaderWithId(user.googleSubjectId.get),
      RawHeader(accessTokenHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    //create a PET service account owned by test user
    samDep.directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, null), ServiceAccount(ServiceAccountSubjectId(user.googleSubjectId.get.value), WorkbenchEmail(petEmail), null))).unsafeRunSync()
    Get("/register/user/v1").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  "GET /admin/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    val (user, getRoutes) = setUpAdminTest()
    Get(s"/api/admin/user/${user.id}").withHeaders(adminHeaders) ~> getRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin with userId to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    val (user, getRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/email/${user.email}").withHeaders(adminHeaders) ~> getRoutes.route ~> check {
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

  it should "not allow a non-admin with user email to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/email/$defaultUserEmail").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Put(s"/api/admin/user/${user.id}/disable").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/${user.id}/enable").withHeaders(adminHeaders) ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
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

    Get(s"/api/admin/user/${user.id}").withHeaders(adminHeaders)~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId").withHeaders(adminHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
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

  "GET /api/users/v1/{email}" should "return the subject id, google subject id, and email for a user" in {
    val (user, headers, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/${user.email.value}").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserIdInfo] shouldEqual UserIdInfo(user.id, user.email, user.googleSubjectId)
    }
  }

  it should "return 404 when the user is not registered" in {
    val (user, headers, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/doesntexist@foo.bar").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
