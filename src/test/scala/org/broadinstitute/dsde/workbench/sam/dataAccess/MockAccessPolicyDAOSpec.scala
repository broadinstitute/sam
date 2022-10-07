package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import scala.language.reflectiveCalls

/** Created by dvoet on 6/26/17.
  */
class MockAccessPolicyDAOSpec extends AnyFlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  private val dummyUser = Generator.genWorkbenchUserGoogle.sample.get

  override protected def beforeAll(): Unit =
    super.beforeAll()

  before {
    TestSupport.truncateAll
  }

  def sharedFixtures = new {
    val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    val policyActions: Set[ResourceAction] =
      accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
    val resourceActions: Set[ResourceAction] = Set(ResourceAction("delete"), SamResourceActions.notifyAdmins) union policyActions
    val resourceActionPatterns: Set[ResourceActionPattern] = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
    val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
    val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
    val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
    val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)
    val managedGroupResourceType =
      ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
    val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
    val emailDomain = "example.com"
  }

  def realServicesFixture = new {
    val shared = sharedFixtures
    val ldapPolicyDao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
    val ldapDirDao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
    val allUsersGroup: WorkbenchGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(ldapDirDao, samRequestContext))

    val policyEvaluatorService = PolicyEvaluatorService(shared.emailDomain, shared.resourceTypes, ldapPolicyDao, ldapDirDao)
    val resourceService =
      new ResourceService(shared.resourceTypes, policyEvaluatorService, ldapPolicyDao, ldapDirDao, NoExtensions, shared.emailDomain, Set.empty)
    val userService = new UserService(ldapDirDao, NoExtensions, Seq.empty, new TosService(ldapDirDao, googleServicesConfig.appsDomain, TestSupport.tosConfig))
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, shared.resourceTypes, ldapPolicyDao, ldapDirDao, NoExtensions, shared.emailDomain)
    shared.resourceTypes foreach { case (_, resourceType) => resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync() }
  }

  def mockServicesFixture = new {
    val shared = sharedFixtures
    val mockDirectoryDAO = new MockDirectoryDAO()
    val mockPolicyDAO = new MockAccessPolicyDAO(shared.resourceTypes, mockDirectoryDAO)
    val allUsersGroup: WorkbenchGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(mockDirectoryDAO, samRequestContext))

    val policyEvaluatorService = PolicyEvaluatorService(shared.emailDomain, shared.resourceTypes, mockPolicyDAO, mockDirectoryDAO)
    val resourceService =
      new ResourceService(shared.resourceTypes, policyEvaluatorService, mockPolicyDAO, mockDirectoryDAO, NoExtensions, shared.emailDomain, Set.empty)
    val userService =
      new UserService(mockDirectoryDAO, NoExtensions, Seq.empty, new TosService(mockDirectoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig))
    val managedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, shared.resourceTypes, mockPolicyDAO, mockDirectoryDAO, NoExtensions, shared.emailDomain)
  }

  "RealAccessPolicyDao and MockAccessPolicyDao" should "return the same results for the same methods" in {
    val real = realServicesFixture
    val mock = mockServicesFixture

    runAndWait(real.userService.createUser(dummyUser, samRequestContext))
    runAndWait(mock.userService.createUser(dummyUser, samRequestContext))

    val groupName = "fooGroup"

    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupName), Set.empty)

    // just compare top level fields because createResource returns the policies, including the default one
    runAndWait(real.managedGroupService.createManagedGroup(ResourceId(groupName), dummyUser, samRequestContext = samRequestContext))
      .copy(accessPolicies = Set.empty) shouldEqual intendedResource
    runAndWait(mock.managedGroupService.createManagedGroup(ResourceId(groupName), dummyUser, samRequestContext = samRequestContext))
      .copy(accessPolicies = Set.empty) shouldEqual intendedResource

    val dummyEmail = WorkbenchEmail("")
    val expectedGroups = Set(ManagedGroupMembershipEntry(ResourceId(groupName), ManagedGroupService.adminRoleName, dummyEmail))
    real.managedGroupService
      .listGroups(dummyUser.id, samRequestContext)
      .unsafeRunSync()
      .map(_.copy(groupEmail = dummyEmail)) should contain theSameElementsAs expectedGroups
    mock.managedGroupService
      .listGroups(dummyUser.id, samRequestContext)
      .unsafeRunSync()
      .map(_.copy(groupEmail = dummyEmail)) should contain theSameElementsAs expectedGroups
  }
}
