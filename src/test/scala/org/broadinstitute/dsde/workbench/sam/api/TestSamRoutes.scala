package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.SwaggerConfig
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, val userInfo: UserInfo, directoryDAO: MockDirectoryDAO, val cloudExtensions: CloudExtensions = NoExtensions)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, managedGroupService, SwaggerConfig("", ""), directoryDAO) with MockUserInfoDirectives with ExtensionRoutes with ScalaFutures {

  def extensionRoutes: server.Route = reject
  def mockDirectoryDao: MockDirectoryDAO = directoryDAO
}

object TestSamRoutes {

  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) = {
    // need to make sure MockDirectoryDAO and MockAccessPolicyDAO share the same groups
    val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()
    val directoryDAO = new MockDirectoryDAO(groups)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO(groups)

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val mockUserService = new UserService(directoryDAO, NoExtensions)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, emailDomain)

    val allUsersGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(directoryDAO))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))

    val mockStatusService = new StatusService(directoryDAO, NoExtensions)

    new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }
}
