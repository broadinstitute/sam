package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.mockito.Mockito.{verify, when}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by gpolumbo on 2/21/2018
  */
class ManagedGroupServiceSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures with OptionValues {

  private val config = ConfigFactory.load()
  val directoryConfig = config.as[DirectoryConfig]("directory")
  val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val resourceId = ResourceId("myNewGroup")
  private val expectedResource = Resource(ManagedGroupService.managedGroupTypeName, resourceId)
  private val adminPolicy = ResourceAndPolicyName(expectedResource, ManagedGroupService.adminPolicyName)
  private val memberPolicy = ResourceAndPolicyName(expectedResource, ManagedGroupService.memberPolicyName)

  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
  private val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap
  private val managedGroupResourceType = resourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))
  private val testDomain = "example.com"

  private val resourceService = new ResourceService(resourceTypeMap, policyDAO, dirDAO, NoExtensions, testDomain)
  private val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, policyDAO, dirDAO, NoExtensions, testDomain)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  def makeResourceType(resourceType: ResourceType): ResourceTypeName = runAndWait(resourceService.createResourceType(resourceType))

  def assertPoliciesOnResource(resource: Resource, policyDAO: JndiAccessPolicyDAO = policyDAO, expectedPolicies: Set[AccessPolicyName] = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName)) = {
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
    assertPoliciesOnResource(resource, expectedPolicies = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName))
    resource
  }

  private def makeGroup(groupName: String, managedGroupService: ManagedGroupService, userInfo: UserInfo = dummyUserInfo) = {
    makeResourceType(managedGroupResourceType)
    runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), userInfo))
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
  }

  "ManagedGroupService create" should "create a managed group with admin and member policies" in {
    assertMakeGroup()
    val policies = runAndWait(policyDAO.listAccessPolicies(expectedResource))
    policies.map(_.id.accessPolicyName.value) shouldEqual Set("admin", "member", "admin-notifier")
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

  it should "sync the new group with Google" in {
    val mockGoogleExtensions = mock[GoogleExtensions]
    val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)
    val groupName = WorkbenchGroupName(resourceId.value)

    when(mockGoogleExtensions.publishGroup(groupName)).thenReturn(Future.successful(()))
    assertMakeGroup(managedGroupService = managedGroupService)
    verify(mockGoogleExtensions).publishGroup(groupName)
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

  it should "succeed after a managed group with the same name has been deleted" in {
    val groupId = ResourceId("uniqueName")
    managedGroupResourceType.reuseIds shouldEqual true
    assertMakeGroup(groupId.value)
    runAndWait(managedGroupService.deleteManagedGroup(groupId))
    assertMakeGroup(groupId.value)
  }

  it should "fail when the group name is too long" in {
    val maxLen = 60
    val groupName = "a" * (maxLen + 1)
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include (s"must be $maxLen characters or fewer")
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
    val groupEmail = WorkbenchEmail(resourceId.value + "@" + testDomain)
    val mockGoogleExtensions = mock[GoogleExtensions]
    when(mockGoogleExtensions.onGroupDelete(groupEmail)).thenReturn(Future.successful(()))
    when(mockGoogleExtensions.publishGroup(WorkbenchGroupName(resourceId.value))).thenReturn(Future.successful(()))
    val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)

    assertMakeGroup(managedGroupService = managedGroupService)
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

  "ManagedGroupService listPolicyMemberEmails" should "return a list of email addresses for the groups admin policy" in {
    val managedGroup = assertMakeGroup()
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(dummyUserInfo.userEmail)
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName)) shouldEqual Set.empty
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.listPolicyMemberEmails(resourceId, ManagedGroupService.adminPolicyName))
    }
  }

  "ManagedGroupService.overwritePolicyMemberEmails" should "permit overwriting the admin policy" in {
    val dummyAdmin = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)
    val otherAdmin = WorkbenchUser(WorkbenchUserId("admin2"), WorkbenchEmail("admin2@foo.test"))
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    runAndWait(dirDAO.createUser(otherAdmin))
    val managedGroup = assertMakeGroup()
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(dummyAdmin.email)

    runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, Set(otherAdmin.email, someGroupEmail)))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(otherAdmin.email, someGroupEmail)
  }

  it should "throw an exception if the group does not exist" in {
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwritePolicyMemberEmails(expectedResource.resourceId, ManagedGroupService.adminPolicyName, Set.empty))
    }
  }

  it should "throw an exception if any of the email addresses do not match an existing subject" in {
    val managedGroup = assertMakeGroup()
    val badAdmin = WorkbenchUser(WorkbenchUserId("admin2"), WorkbenchEmail("admin2@foo.test"))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, Set(badAdmin.email)))
    }
  }

  it should "permit overwriting the member policy" in {
    val managedGroup = assertMakeGroup()

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), WorkbenchEmail("someUser@foo.test"))
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    runAndWait(dirDAO.createUser(someUser))
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName)) shouldEqual Set()

    val newMembers = Set(someGroupEmail, someUser.email)
    runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, newMembers))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName)) shouldEqual newMembers
  }

  "ManagedGroupService addSubjectToPolicy" should "successfully add the subject to the existing policy for the group" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), WorkbenchEmail("someUser@foo.test"))
    runAndWait(dirDAO.createUser(someUser))
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, someUser.id))

    val expectedEmails = Set(adminUser.email, someUser.email)
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual expectedEmails
  }

  it should "succeed without changing if the email address is already in the policy" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id))
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)
  }

  // TODO: Is this right?  ResourceService.overwriteResource fails with invalid emails, should addSubjectToPolicy fail too?
  // The correct behavior is enforced in the routing, but is that the right place?  Should it be enforced in the Service class?
  it should "succeed even if the subject is doesn't exist" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), WorkbenchEmail("someUser@foo.test"))
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, someUser.id))
  }

  "ManagedGroupService removeSubjectFromPolicy" should "successfully remove the subject from the policy for the group" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    runAndWait(managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set.empty
  }

  it should "not do anything if the subject is not a member of the policy" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    runAndWait(managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, WorkbenchUserId("someUser")))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)
  }

  private def makeResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Resource = runAndWait(resourceService.createResource(resourceType, resourceId, userInfo))

  "ManagedGroupService listGroups" should "return the list of groups that passed user belongs to" in {
    // Setup multiple managed groups owned by different users.
    // Make the different users a member of some of the groups owned by the other user
    // Create some resources owned by different users
    // List Managed Group memberships for users and assert that memberships are only returned for managed groups
    val newResourceType = managedGroupResourceType.copy(name = ResourceTypeName(UUID.randomUUID().toString))
    makeResourceType(newResourceType)
    val resTypes = resourceTypeMap + (newResourceType.name -> newResourceType)

    val resService = new ResourceService(resTypes, policyDAO, dirDAO, NoExtensions, testDomain)
    val mgService = new ManagedGroupService(resService, resTypes, policyDAO, dirDAO, NoExtensions, testDomain)

    val user1 = UserInfo(OAuth2BearerToken("token1"), WorkbenchUserId("userId1"), WorkbenchEmail("user1@company.com"), 0)
    val user2 = UserInfo(OAuth2BearerToken("token2"), WorkbenchUserId("userId2"), WorkbenchEmail("user2@company.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(user1.userId, user1.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(user2.userId, user2.userEmail)))

    val user1Groups = Set("foo", "bar", "baz")
    val user2Groups = Set("qux", "quux")
    user1Groups.foreach(makeGroup(_, mgService, user1))
    user2Groups.foreach(makeGroup(_, mgService, user2))

    val user1Memberships = Set(user2Groups.head)
    val user2Memberships = Set(user1Groups.head)

    user1Memberships.foreach(s => mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user1.userId))
    user2Memberships.foreach(s => mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user2.userId))

    val user1Resources = Set("quuz", "corge")
    val user2Resources = Set("grault", "garply")

    user1Resources.foreach(s => makeResource(newResourceType, ResourceId(s), user1))
    user2Resources.foreach(s => makeResource(newResourceType, ResourceId(s), user2))

    val user1ExpectedAdmin = user1Groups.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.adminPolicyName, WorkbenchEmail(s"$s@example.com")))
    val user1ExpectedMember = user1Memberships.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.memberPolicyName, WorkbenchEmail(s"$s@example.com")))
    val user1ExpectedGroups = user1ExpectedAdmin ++ user1ExpectedMember
    runAndWait(mgService.listGroups(user1.userId)) shouldEqual user1ExpectedGroups

    val user2ExpectedAdmin = user2Groups.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.adminPolicyName, WorkbenchEmail(s"$s@example.com")))
    val user2ExpectedMember = user2Memberships.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.memberPolicyName, WorkbenchEmail(s"$s@example.com")))
    val user2ExpectedGroups = user2ExpectedAdmin ++ user2ExpectedMember
    runAndWait(mgService.listGroups(user2.userId)) shouldEqual user2ExpectedGroups
  }

}
