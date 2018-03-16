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
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.mockito.Mockito.{verify, when}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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
  private val expectedResource = Resource(ManagedGroupService.managedGroupTypeName, resourceId)
  private val ownerRoleName = ResourceRoleName("admin")
  private val ownerPolicyName = AccessPolicyName(ownerRoleName.value)
  private val memberPolicyName = AccessPolicyName(ManagedGroupService.memberRoleName.value)
  private val adminPolicy = ResourceAndPolicyName(expectedResource, ownerPolicyName)
  private val memberPolicy = ResourceAndPolicyName(expectedResource, memberPolicyName)
  private val accessPolicyNames = Set(ownerPolicyName, memberPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ownerRoleName, resourceActions)
  private val defaultRoles = Set(defaultOwnerRole, ResourceRole(ManagedGroupService.memberRoleName, Set.empty))
  private val managedGroupResourceType = ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ownerRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val testDomain = "example.com"
  private val resourceService = new ResourceService(resourceTypes, policyDAO, dirDAO, NoExtensions, testDomain)

  private val managedGroupService = new ManagedGroupService(resourceService, resourceTypes, policyDAO, dirDAO, NoExtensions, testDomain)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  def makeResourceType(): ResourceTypeName = runAndWait(resourceService.createResourceType(managedGroupResourceType))

  def assertPoliciesOnResource(resource: Resource, policyDAO: JndiAccessPolicyDAO = policyDAO, expectedPolicies: Set[AccessPolicyName] = Set(ownerPolicyName, memberPolicyName)) = {
    val policies = runAndWait(policyDAO.listAccessPolicies(resource))
    policies.map(_.id.accessPolicyName.value) shouldEqual expectedPolicies.map(_.value)
    expectedPolicies.foreach { policyName =>
      runAndWait(policyDAO.loadPolicy(ResourceAndPolicyName(resource, policyName))) shouldBe a[Some[AccessPolicy]]
    }
  }

  def assertMakeGroup(groupId: String = resourceId.value, managedGroupService: ManagedGroupService = managedGroupService, policyDAO: JndiAccessPolicyDAO = policyDAO): Resource = {
    val resource: Resource = makeGroup(groupId, managedGroupService)
    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    resource shouldEqual intendedResource
    assertPoliciesOnResource(resource, expectedPolicies = Set(ownerPolicyName, memberPolicyName))
    resource
  }

  private def makeGroup(groupName: String, managedGroupService: ManagedGroupService, userInfo: UserInfo = dummyUserInfo) = {
    makeResourceType()
    runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), userInfo))
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
    runAndWait(managedGroupService.loadManagedGroup(resourceId)) shouldEqual None
  }

  it should "fail when the group name is too long" in {
    val groupName = "a" * 64
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include ("Email address length must be shorter than 64 characters")
    runAndWait(managedGroupService.loadManagedGroup(resourceId)) shouldEqual None
  }

  it should "fail when the group name has invalid characters" in {
    val groupName = "Make It Rain!!! $$$$$"
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include ("Group name may only contain alphanumeric characters, underscores, and dashes")
    runAndWait(managedGroupService.loadManagedGroup(resourceId)) shouldEqual None
  }

  "ManagedGroupService get" should "return the Managed Group resource" in {
    assertMakeGroup()
    val maybeEmail = runAndWait(managedGroupService.loadManagedGroup(resourceId))
    maybeEmail.value.value shouldEqual s"${resourceId.value}@$testDomain"
  }

  // NOTE: All since we don't have a way to look up policies directly without going through a Resource, this test
  // may not be actually confirming that the policies have been deleted.  They may still be in LDAP, just orphaned
  // because the resource no longer exists
  "ManagedGroupService delete" should "delete policies associated to that resource in LDAP and in Google" in {
    val mockGoogleExtensions = mock[GoogleExtensions]
    val managedGroupService = new ManagedGroupService(resourceService, resourceTypes, policyDAO, dirDAO, mockGoogleExtensions, testDomain)
    val groupEmail = WorkbenchEmail(resourceId.value + "@" + testDomain)

    assertMakeGroup(managedGroupService = managedGroupService)
    when(mockGoogleExtensions.onGroupDelete(groupEmail)).thenReturn(Future.successful(()))
    runAndWait(managedGroupService.deleteManagedGroup(resourceId))
    verify(mockGoogleExtensions).onGroupDelete(groupEmail)
    runAndWait(policyDAO.listAccessPolicies(expectedResource)) shouldEqual Set.empty
    runAndWait(policyDAO.loadPolicy(adminPolicy)) shouldEqual None
    runAndWait(policyDAO.loadPolicy(memberPolicy)) shouldEqual None
  }

  it should "fail if the managed group is a sub group of any other workbench group" in {
    val managedGroup = assertMakeGroup("coolGroup")
    val managedGroupName = WorkbenchGroupName(managedGroup.resourceId.value)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(managedGroupName), WorkbenchEmail("foo@foo.gov"))

    runAndWait(dirDAO.createGroup(parentGroup)) shouldEqual parentGroup

    // using .get on an option here because if the Option is None and this throws an exception, that's fine
    runAndWait(dirDAO.loadGroup(parentGroup.id)).get.members shouldEqual Set(managedGroupName)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.deleteManagedGroup(managedGroup.resourceId))
    }

    runAndWait(managedGroupService.loadManagedGroup(managedGroup.resourceId)) shouldNot be (None)
    runAndWait(dirDAO.loadGroup(parentGroup.id)).get.members shouldEqual Set(managedGroupName)

  }

  "ManagedGroupService listAdminEmails" should "return a list of email addresses for the groups admin policy" in {
    val managedGroup = assertMakeGroup()
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual Set(dummyUserInfo.userEmail)
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.listAdminEmails(expectedResource))
    }
  }

  "ManagedGroupService listMemberEmails" should "return a list of email addresses for the groups member policy" in {
    val managedGroup = assertMakeGroup()
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual Set.empty
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.listMemberEmails(expectedResource))
    }
  }

  "ManagedGroupService.overwriteAdminEmails" should "permit overwriting with 'user' subjects" in {
    val managedGroup = assertMakeGroup()
    val dummyAdmin = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)
    val otherAdmin = WorkbenchUser(WorkbenchUserId("admin2"), WorkbenchEmail("admin2@foo.test"))
    runAndWait(dirDAO.createUser(dummyAdmin))
    runAndWait(dirDAO.createUser(otherAdmin))

    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual Set(dummyAdmin.email)

    runAndWait(managedGroupService.overwriteAdminEmails(managedGroup, Set(otherAdmin.email)))

    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual Set(otherAdmin.email)
  }

  it should "permit overwriting with 'group' subjects" in {
    val managedGroup = assertMakeGroup()

    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)))

    val dummyAdmin = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)
    runAndWait(dirDAO.createUser(dummyAdmin))

    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual Set(dummyAdmin.email)

    val newAdmins = Set(dummyAdmin.email, someGroupEmail)
    runAndWait(managedGroupService.overwriteAdminEmails(managedGroup, newAdmins))

    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual newAdmins
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwriteAdminEmails(expectedResource, Set.empty))
    }
  }

  it should "throw an exception if any of the email addresses do not match an existing subject" in {
    val managedGroup = assertMakeGroup()
    val badAdmin = WorkbenchUser(WorkbenchUserId("admin2"), WorkbenchEmail("admin2@foo.test"))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwriteAdminEmails(managedGroup, Set(badAdmin.email)))
    }
  }

  "ManagedGroupService.overwriteMemberEmails" should "permit overwriting with 'group' subjects" in {
    val managedGroup = assertMakeGroup()

    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)))

    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual Set()

    val newMembers = Set(someGroupEmail)
    runAndWait(managedGroupService.overwriteMemberEmails(managedGroup, newMembers))

    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual newMembers
  }

  it should "permit overwriting with 'user' subjects" in {
    val managedGroup = assertMakeGroup()
    val joeSchmoe = WorkbenchUser(WorkbenchUserId("joe"), WorkbenchEmail("joe.schmoe@foo.test"))
    runAndWait(dirDAO.createUser(joeSchmoe))

    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual Set()

    runAndWait(managedGroupService.overwriteMemberEmails(managedGroup, Set(joeSchmoe.email)))

    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual Set(joeSchmoe.email)
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwriteMemberEmails(expectedResource, Set.empty))
    }
  }

  it should "throw an exception if any of the email addresses do not match an existing subject" in {
    val managedGroup = assertMakeGroup()
    val badUser = WorkbenchUser(WorkbenchUserId("MrNobody"), WorkbenchEmail("null@foo.test"))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwriteMemberEmails(managedGroup, Set(badUser.email)))
    }
  }

  "ManagedGroupService overwriting policy members" should "permit overwriting with many subjects and different types" in {
    val managedGroup = assertMakeGroup()

    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    val xmenEmail = WorkbenchEmail("xmen@xavier.org")
    val joeSchmoe = WorkbenchUser(WorkbenchUserId("joe"), WorkbenchEmail("joe.schmoe@foo.test"))
    val dummyAdmin = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)
    val otherAdmin = WorkbenchUser(WorkbenchUserId("admin2"), WorkbenchEmail("admin2@foo.test"))

    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)))
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("xmen"), Set.empty, xmenEmail)))
    runAndWait(dirDAO.createUser(joeSchmoe))
    runAndWait(dirDAO.createUser(dummyAdmin))
    runAndWait(dirDAO.createUser(otherAdmin))

    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual Set(dummyAdmin.email)
    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual Set()

    val newMembers = Set(someGroupEmail, joeSchmoe.email)
    runAndWait(managedGroupService.overwriteMemberEmails(managedGroup, newMembers))
    runAndWait(managedGroupService.listMemberEmails(managedGroup)) shouldEqual newMembers

    val newAdmins = Set(xmenEmail, dummyAdmin.email, otherAdmin.email)
    runAndWait(managedGroupService.overwriteAdminEmails(managedGroup, newAdmins))
    runAndWait(managedGroupService.listAdminEmails(managedGroup)) shouldEqual newAdmins
  }
}
