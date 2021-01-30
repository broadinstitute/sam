package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.net.URI

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.reflectiveCalls
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Created by dvoet on 6/26/17.
  */
class MockAccessPolicyDAOSpec extends AnyFlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig: DirectoryConfig = TestSupport.appConfig.directoryConfig
  val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)
  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    TestSupport.truncateAll
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  def sharedFixtures = new {
    val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()
    val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
    val resourceActions: Set[ResourceAction] = Set(ResourceAction("delete"), SamResourceActions.notifyAdmins) union policyActions
    val resourceActionPatterns: Set[ResourceActionPattern] = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
    val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
    val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
    val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
    val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)
    val managedGroupResourceType = ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
    val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
    val emailDomain = "example.com"
  }

  def realServicesFixture = new {
    val shared = sharedFixtures
    val dirURI = new URI(directoryConfig.directoryUrl)
    val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
    val ldapPolicyDao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
    val ldapDirDao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
    val registrationDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
    val allUsersGroup: WorkbenchGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(ldapDirDao, samRequestContext))

    val policyEvaluatorService = PolicyEvaluatorService(shared.emailDomain, shared.resourceTypes, ldapPolicyDao, ldapDirDao)
    val resourceService = new ResourceService(shared.resourceTypes, policyEvaluatorService, ldapPolicyDao, ldapDirDao, NoExtensions, shared.emailDomain)
    val userService = new UserService(ldapDirDao, NoExtensions, registrationDAO, Seq.empty)
    val managedGroupService = new ManagedGroupService(resourceService, policyEvaluatorService, shared.resourceTypes, ldapPolicyDao, ldapDirDao, NoExtensions, shared.emailDomain)
    shared.resourceTypes foreach {case (_, resourceType) => resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync() }
  }

  def mockServicesFixture = new {
    val shared = sharedFixtures
    val mockDirDao = new MockDirectoryDAO(shared.groups)
    val mockRegDao = new MockDirectoryDAO()
    val mockPolicyDAO = new MockAccessPolicyDAO(shared.resourceTypes, shared.groups)
    val allUsersGroup: WorkbenchGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(mockDirDao, samRequestContext))

    val policyEvaluatorService = PolicyEvaluatorService(shared.emailDomain, shared.resourceTypes, mockPolicyDAO, mockDirDao)
    val resourceService = new ResourceService(shared.resourceTypes, policyEvaluatorService, mockPolicyDAO, mockDirDao, NoExtensions, shared.emailDomain)
    val userService = new UserService(mockDirDao, NoExtensions, mockRegDao, Seq.empty)
    val managedGroupService = new ManagedGroupService(resourceService, policyEvaluatorService, shared.resourceTypes, mockPolicyDAO, mockDirDao, NoExtensions, shared.emailDomain)
  }

  "RealAccessPolicyDao and MockAccessPolicyDao" should "return the same results for the same methods" in {
    val real = realServicesFixture
    val mock = mockServicesFixture

    val dummyUser = CreateWorkbenchUser(dummyUserInfo.userId, GoogleSubjectId(dummyUserInfo.userId.value), dummyUserInfo.userEmail, None)
    runAndWait(real.userService.createUser(dummyUser, samRequestContext))
    runAndWait(mock.userService.createUser(dummyUser, samRequestContext))

    val groupName = "fooGroup"

    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupName), Set.empty)

    // just compare top level fields because createResource returns the policies, including the default one
    runAndWait(real.managedGroupService.createManagedGroup(ResourceId(groupName), dummyUserInfo, samRequestContext = samRequestContext)).copy(accessPolicies = Set.empty) shouldEqual intendedResource
    runAndWait(mock.managedGroupService.createManagedGroup(ResourceId(groupName), dummyUserInfo, samRequestContext = samRequestContext)).copy(accessPolicies = Set.empty) shouldEqual intendedResource


    val dummyEmail = WorkbenchEmail("")
    val expectedGroups = Set(ManagedGroupMembershipEntry(ResourceId(groupName), ManagedGroupService.adminRoleName, dummyEmail))
    real.managedGroupService.listGroups(dummyUserInfo.userId, samRequestContext).unsafeRunSync().map(_.copy(groupEmail = dummyEmail)) should contain theSameElementsAs expectedGroups
    mock.managedGroupService.listGroups(dummyUserInfo.userId, samRequestContext).unsafeRunSync().map(_.copy(groupEmail = dummyEmail)) should contain theSameElementsAs expectedGroups
  }
}
