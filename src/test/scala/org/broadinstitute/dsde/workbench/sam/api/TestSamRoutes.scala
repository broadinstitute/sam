package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.SwaggerConfig
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName, UserInfo}
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, userService: UserService, statusService: StatusService, val userInfo: UserInfo, val cloudExtensions: CloudExtensions = NoExtensions)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, SwaggerConfig("", "")) with MockUserInfoDirectives with ExtensionRoutes with ScalaFutures {

  def extensionRoutes: server.Route = reject
}

object TestSamRoutes {

  val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchUserEmail("user1@example.com"), 0)

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) = {
    val directoryDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO()

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, NoExtensions, "example.com")
    val mockUserService = new UserService(directoryDAO, NoExtensions)

    val allUsersGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(directoryDAO))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))

    val mockStatusService = new StatusService(directoryDAO, NoExtensions)

    new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, userInfo)
  }
}
