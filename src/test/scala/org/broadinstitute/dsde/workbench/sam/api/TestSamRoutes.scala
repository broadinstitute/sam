package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.SwaggerConfig
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName, UserInfo}
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, userService: UserService, statusService: StatusService, val userInfo: UserInfo)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, SwaggerConfig("", "")) with MockUserInfoDirectives with NoExtensionRoutes

object TestSamRoutes {

  val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchUserEmail("user1@example.com"), 0)

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) = {
    val directoryDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO()

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, NoExtensions, "example.com")
    val mockUserService = new UserService(directoryDAO, NoExtensions, googleDirectoryDAO, "dev.test.firecloud.org")

    TestSupport.runAndWait(mockUserService.createAllUsersGroup)

    val mockStatusService = new StatusService(directoryDAO, googleDirectoryDAO)

    new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, userInfo)
  }
}
