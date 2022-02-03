package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.genInviteUser
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport.rootBooleanJsonFormat
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, TosService, UserService}

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesV1Spec extends UserRoutesSpecHelper{

  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v1/" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "create a user and accept the tos when the user specifies the ToS body correctly" in {
    val (_, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)
    val tos = TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")

    Post("/register/user/v1/termsofservice", tos) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
    }
  }

  it should "forbid the registration if ToS is enabled and the user doesn't specify the correct ToS url" in {
    val (_, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)

    val tos = TermsOfServiceAcceptance("onemillionpats.com")
    Post("/register/user/v1/termsofservice", tos) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[ErrorReport].message should startWith("You must accept the Terms of Service in order to register.")
    }
  }

  it should "get user's registration status after accepting the tos" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatus]
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
    }
  }

  "GET /register/user/v1/termsofservice/status" should "return 404 when ToS is disabled" in {
    val (_, _, routes) = createTestUser(tosEnabled = false)

    Get("/register/user/v1/termsofservice") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 200 + false when ToS is enabled but the user hasn't accepted the ToS" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)

    Get("/register/user/v1/termsofservice") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[Boolean]
      res shouldBe false
    }
  }

  it should "return 200 + false when ToS is enabled but the user hasn't accepted the ToS" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v1/termsofservice") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[Boolean]
      res shouldBe true
    }
  }

  "POST /api/users/v1/invite/{invitee's email}" should "create user" in {
    val invitee = genInviteUser.sample.get

    val (user, _, routes) = createTestUser() //create a valid user that can invite someone
    Post(s"/api/users/v1/invite/${invitee.inviteeEmail}") ~> routes.route ~> check{
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatusDetails]
      res.userEmail shouldBe invitee.inviteeEmail
    }
  }

  "GET /register/user/v1/" should "get the status of an enabled user" in {
    val (user, samDep, routes) = createTestUser()

    Get("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1?userDetailsOnly=true") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map.empty)
    }
  }

  "GET /admin/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    val (user, getRoutes) = setUpAdminTest()
    Get(s"/api/admin/user/${user.id}") ~> getRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin with userId to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    val (user, getRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/email/${user.email}") ~> getRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "return 404 for an unknown user by email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/XXX${defaultUserEmail}XXX") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 for an group's email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/fc-admins@dev.test.firecloud.org") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin with user email to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Put(s"/api/admin/user/${user.id}/disable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/${user.id}/enable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin to enable or disable a user" in withAdminRoutes { (samRoutes, _) =>
    Put(s"/api/admin/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }

    Put(s"/api/admin/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}" should "delete a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/user/${user.id}")~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}/petServiceAccount/myproject") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/users/v1/{email}" should "return the subject id, google subject id, and email for a user" in {
    val (user, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/${user.email.value}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserIdInfo] shouldEqual UserIdInfo(user.id, user.email, user.googleSubjectId)
    }
  }

  it should "return 404 when the user is not registered" in {
    val (user, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/doesntexist@foo.bar") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
