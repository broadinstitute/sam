package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.scalatest.{FlatSpec, Matchers}

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  "UserRoutes" should "create user" in {
    val userId = SamUserId("newuser")
    val userEmail = SamUserEmail("newuser@new.com")
    val samRoutes = new TestSamRoutes(Map.empty, null, new UserService(new MockDirectoryDAO()), UserInfo("", userId, userEmail, 0))

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUser] shouldEqual SamUser(userId, userEmail)
    }

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }
}

