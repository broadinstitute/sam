package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, googleServicesConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/** Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends UserRoutesSpecHelper {
  "POST /register/user" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe TestSupport.enabledMapNoTosAccepted
    }

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user" should "get the status of an enabled user" in {
    val (user, _, routes) = createTestUser()

    Get("/register/user") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), TestSupport.enabledMapNoTosAccepted)
    }
  }
}

trait UserRoutesSpecHelper extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId = defaultUser.id
  val defaultUserEmail = defaultUser.email

  val adminUser = Generator.genWorkbenchUserGoogle.sample.get

  val petSAUser = Generator.genWorkbenchUserServiceAccount.sample.get
  val petSAUserId = petSAUser.id
  val petSAEmail = petSAUser.email

  def createTestUser(
      testUser: SamUser = Generator.genWorkbenchUserBoth.sample.get,
      cloudExtensions: Option[CloudExtensions] = None,
      googleDirectoryDAO: Option[GoogleDirectoryDAO] = None,
      tosAccepted: Boolean = false
  ): (SamUser, SamDependencies, SamRoutes) = {
    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO)
    val routes = genSamRoutes(samDependencies, testUser)

    Post("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe testUser.email
      val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false, "adminEnabled" -> true)
      res.enabled shouldBe enabledBaseArray
    }

    if (tosAccepted) {
      Post("/register/user/v1/termsofservice", TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")) ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
        val res = responseAs[UserStatus]
        res.userInfo.userEmail shouldBe testUser.email
        val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
        res.enabled shouldBe enabledBaseArray
      }
    }

    (testUser, samDependencies, routes)
  }

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val samRoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, NoExtensions, Seq.empty, tosService),
      new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef),
      null,
      defaultUser,
      directoryDAO,
      newSamUser = Option(defaultUser),
      tosService = tosService
    )

    testCode(samRoutes)
  }

}
