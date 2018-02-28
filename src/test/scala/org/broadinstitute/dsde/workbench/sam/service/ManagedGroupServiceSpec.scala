package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest._
import net.ceedubs.ficus.Ficus._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.runtime.BoxedUnit

/**
  * Created by gpolumbo on 2/21/2018
  */
class ManagedGroupServiceSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures with OptionValues {

  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  private val resourceId = ResourceId("myNewGroup")
  private val expectedResource = Resource(ManagedGroupService.ManagedGroupTypeName, resourceId)
  private val ownerRoleName = ResourceRoleName("admin")
  private val ownerPolicyName = AccessPolicyName(ownerRoleName.value)
  private val memberPolicyName = AccessPolicyName(ManagedGroupService.MemberRoleName.value)
  private val adminPolicy = ResourceAndPolicyName(expectedResource, ownerPolicyName)
  private val memberPolicy = ResourceAndPolicyName(expectedResource, memberPolicyName)
  private val accessPolicyNames = Set(ownerPolicyName, memberPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ownerRoleName, resourceActions)
  private val defaultRoles = Set(defaultOwnerRole, ResourceRole(ManagedGroupService.MemberRoleName, Set.empty))
  private val managedGroupResourceType = ResourceType(ManagedGroupService.ManagedGroupTypeName, resourceActionPatterns, defaultRoles, ownerRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val testDomain = "example.com"
  private val resourceService = new ResourceService(resourceTypes, policyDAO, dirDAO, NoExtensions, testDomain)

  val managedGroupService = new ManagedGroupService(resourceService, resourceTypes)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  def makeResourceType(): ResourceTypeName = runAndWait(resourceService.createResourceType(managedGroupResourceType))

  def assertMakeGroup(groupId: String = resourceId.value): Resource = {
    makeResourceType()
    val intendedId = ResourceId(groupId)
    val intendedResource = Resource(ManagedGroupService.ManagedGroupTypeName, intendedId)
    val resource = runAndWait(managedGroupService.createManagedGroup(intendedId, dummyUserInfo))
    resource shouldEqual intendedResource
    resource
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  "ManagedGroupService create" should "create a managed group with admin and member policies" in {
    assertMakeGroup()
    val policies = runAndWait(policyDAO.listAccessPolicies(expectedResource))
    policies.map(_.id.accessPolicyName.value) shouldEqual Set("admin", "member")
  }

  it should "create a workbenchGroup with the same name as the Managed Group" in {
    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = runAndWait(dirDAO.loadGroup(WorkbenchGroupName(resourceId.value)))
    samGroup.value.id.value shouldEqual resourceId.value
  }

  it should "create a workbenchGroup with 2 member WorkbenchSubjects" in {
    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = runAndWait(dirDAO.loadGroup(WorkbenchGroupName(resourceId.value)))
    samGroup.value.members shouldEqual Set(adminPolicy, memberPolicy)
  }

  it should "fail when trying to create a group that already exists" in {
    val groupName = "uniqueName"
    assertMakeGroup(groupName)
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), dummyUserInfo))
    }
    exception.getMessage should include ("A resource of this type and name already exists")
    runAndWait(managedGroupService.loadManagedGroup(resourceId)).isEmpty shouldEqual true
  }

  it should "fail when the group name is too long" in {
    val groupName = "a" * 64
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include ("Email address length must be shorter than 64 characters")
    runAndWait(managedGroupService.loadManagedGroup(resourceId)).isEmpty shouldEqual true
  }

  it should "fail when the group name has invalid characters" in {
    val groupName = "Make It Rain!!! $$$$$"
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include ("Group name may only contain alphanumeric characters, underscores, and dashes")
    runAndWait(managedGroupService.loadManagedGroup(resourceId)).isEmpty shouldEqual true
  }

  "ManagedGroupService get" should "return the Managed Group resource" in {
    assertMakeGroup()
    val maybeEmail = runAndWait(managedGroupService.loadManagedGroup(resourceId))
    maybeEmail.value.value shouldEqual s"${resourceId.value}@$testDomain"
  }

  // NOTE: All since we don't have a way to look up policies directly without going through a Resource, this test
  // may not be actually confirming that the policies have been deleted.  They may still be in LDAP, just orphaned
  // because the resource no longer exists
  "ManagedGroupService delete" should "delete policies associated to that resource" in {
    assertMakeGroup()
    runAndWait(policyDAO.listAccessPolicies(expectedResource)).isEmpty shouldEqual false
    runAndWait(policyDAO.loadPolicy(adminPolicy)).isDefined shouldEqual true
    runAndWait(policyDAO.loadPolicy(memberPolicy)).isDefined shouldEqual true

    val delResponse = runAndWait(managedGroupService.deleteManagedGroup(resourceId))
    delResponse shouldEqual ()

    runAndWait(policyDAO.listAccessPolicies(expectedResource)).isEmpty shouldEqual true
    runAndWait(policyDAO.loadPolicy(adminPolicy)).isDefined shouldEqual false
    runAndWait(policyDAO.loadPolicy(memberPolicy)).isDefined shouldEqual false
  }

}
