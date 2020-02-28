package org.broadinstitute.dsde.workbench.sam
package api


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, UserService}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import StandardUserInfoDirectives._
/**
  * Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends UserRoutesSpecHelper {
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v2/self" should "create user" in withDefaultRoutes { samRoutes =>
    val header = TestSupport.genGoogleSubjectIdHeader

    Post("/register/user/v2/self").withHeaders(header, TestSupport.defaultEmailHeader) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v2/self").withHeaders(header, TestSupport.defaultEmailHeader) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user/v2/self/info" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val firstGetHeaders = List(
      defaultEmailHeader,
      TestSupport.googleSubjectIdHeaderWithId(googleSubjectId),
      RawHeader(accessTokenHeader, ""),
      RawHeader(authorizationHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    Get("/register/user/v2/self/info").withHeaders(firstGetHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, headers, samDep, routes) = createTestUser(googSubjectId = Some(googleSubjectId))
    Get("/register/user/v2/self/info").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(user.id.value, user.email.value, true)
    }
  }

  "GET /register/user/v2/self/diagnostics" should "get the diagnostic info for an enabled user" in withDefaultRoutes { samRoutes =>
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val firstGetHeaders = List(
      defaultEmailHeader,
      TestSupport.googleSubjectIdHeaderWithId(googleSubjectId),
      RawHeader(accessTokenHeader, ""),
      RawHeader(authorizationHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    Get("/register/user/v2/self/diagnostics").withHeaders(firstGetHeaders) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, headers, samDep, routes) = createTestUser(googSubjectId = Some(googleSubjectId))

    Get("/register/user/v2/self/diagnostics").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusDiagnostics] shouldEqual UserStatusDiagnostics(true, true, true)
    }
  }

  "POST /register/user/v2/self" should "retrieve PET owner's user info if a PET account is provided" in {
    val (user, _, samDep, routes) = createTestUser()
    val petEmail = s"pet-${user.id.value}@test.iam.gserviceaccount.com"
    val headers = List(
      RawHeader(emailHeader, petEmail),
      TestSupport.googleSubjectIdHeaderWithId(user.googleSubjectId.get),
      RawHeader(accessTokenHeader, ""),
      RawHeader(authorizationHeader, ""),
      RawHeader(expiresInHeader, "1000")
    )
    //create a PET service account owned by test user
    samDep.directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, null), ServiceAccount(ServiceAccountSubjectId(user.googleSubjectId.get.value), WorkbenchEmail(petEmail), null))).unsafeRunSync()

    Get("/register/user/v2/self/info").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(user.id.value, user.email.value, true)
    }
  }
}
