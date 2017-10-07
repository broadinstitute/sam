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
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, StatusService, UserService}

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(val resourceTypes: Map[ResourceTypeName, ResourceType], resourceService: ResourceService, userService: UserService, statusService: StatusService, val userInfo: UserInfo)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, SwaggerConfig("", "")) with MockUserInfoDirectives

object TestSamRoutes {
  def apply(resourceTypes: Map[ResourceTypeName, ResourceType])(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) = {
    val directoryDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO()

    val mockResourceService = new ResourceService(policyDAO, directoryDAO, "example.com")
    val mockUserService = new UserService(directoryDAO, googleDirectoryDAO, "dev.test.firecloud.org")

    TestSupport.runAndWait(mockUserService.createAllUsersGroup)

    val mockStatusService = new StatusService(directoryDAO, googleDirectoryDAO)

    new TestSamRoutes(resourceTypes, mockResourceService, mockUserService, mockStatusService, UserInfo("", WorkbenchUserId(""), WorkbenchUserEmail(""), 0))
  }
}