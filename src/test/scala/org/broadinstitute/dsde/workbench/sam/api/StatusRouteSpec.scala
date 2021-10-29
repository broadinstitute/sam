package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{LdapRegistrationDAO, MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, OpenDJ}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse}
import org.scalatest.concurrent.Eventually._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import scala.concurrent.duration._

class StatusRouteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {


  "GET /version" should "give 200 for ok" in {
    val samRoutes = TestSamRoutes(Map.empty)
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    eventually {
      Get("/version") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] should include("n/a")
      }
    }
  }

  "GET /status" should "give 200 for ok" in {
    val samRoutes = TestSamRoutes(Map.empty)
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    eventually {
      Get("/status") ~> samRoutes.route ~> check {
        responseAs[StatusCheckResponse] shouldEqual StatusCheckResponse(true, Map(OpenDJ -> HealthMonitor.OkStatus, Database -> HealthMonitor.OkStatus))
        status shouldEqual StatusCodes.OK
      }
    }
  }

  it should "give 500 for not ok" in {
    val directoryConfig: DirectoryConfig = TestSupport.directoryConfig
    val dirURI = new URI(directoryConfig.directoryUrl)
    val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
    connectionPool.close()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
    val policyDAO = new MockAccessPolicyDAO()

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(Map.empty, null, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, googleServicesConfig.appsDomain))
    val mockStatusService = new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, null, Map.empty, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, Map.empty, policyDAO, directoryDAO)

    val samRoutes = new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, UserInfo(OAuth2BearerToken(""), WorkbenchUserId(""), WorkbenchEmail(""), 0), directoryDAO)

    Get("/status") ~> samRoutes.route ~> check {
      responseAs[StatusCheckResponse].ok shouldEqual false
      status shouldEqual StatusCodes.InternalServerError
    }
  }
}
