package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, MockAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, policyEvaluatorService: PolicyEvaluatorService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, val userInfo: UserInfo, directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions = NoExtensions, override val createWorkbenchUser: Option[CreateWorkbenchUser] = None)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, managedGroupService, SwaggerConfig("", ""), directoryDAO, policyEvaluatorService, LiquibaseConfig("", false)) with MockUserInfoDirectives with ExtensionRoutes with ScalaFutures {
  def extensionRoutes: server.Route = reject
  def mockDirectoryDao: DirectoryDAO = directoryDAO
}

object TestSamRoutes {
  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
  val defaultGoogleSubjectId = GoogleSubjectId("user1")

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo, policyAccessDAO: Option[AccessPolicyDAO] = None, policies: Option[mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup]] = None)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext, contextShift: ContextShift[IO]) = {
    // need to make sure MockDirectoryDAO and MockAccessPolicyDAO share the same groups
    val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = policies.getOrElse(new TrieMap())
    val directoryDAO = new MockDirectoryDAO(groups)
    val registrationDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = policyAccessDAO.getOrElse(new MockAccessPolicyDAO(groups))

    val emailDomain = "example.com"
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypes, policyDAO, directoryDAO)
    val mockResourceService = new ResourceService(resourceTypes, policyEvaluatorService, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, policyDAO, directoryDAO, NoExtensions, emailDomain)
    TestSupport.runAndWait(mockUserService.createUser(
      CreateWorkbenchUser(userInfo.userId, defaultGoogleSubjectId, userInfo.userEmail, None)))
    val allUsersGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(directoryDAO))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))

    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }
}
