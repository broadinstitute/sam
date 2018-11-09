package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.LdapDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LdapAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.mockito.ArgumentMatchers
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
  val directoryConfig = TestSupport.appConfig.directoryConfig
  val schemaLockConfig = TestSupport.appConfig.schemaLockConfig
  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val resourceTypes = TestSupport.appConfig.resourceTypes
  private val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap

  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val resourceId = ResourceId("myNewGroup")
  private val expectedResource = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId)
  private val adminPolicy = FullyQualifiedPolicyId(expectedResource, ManagedGroupService.adminPolicyName)
  private val memberPolicy = FullyQualifiedPolicyId(expectedResource, ManagedGroupService.memberPolicyName)

  private val managedGroupResourceType = resourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))
  private val testDomain = "example.com"

  private val policyEvaluatorService = PolicyEvaluatorService(testDomain, resourceTypeMap, policyDAO)
  private val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, policyDAO, dirDAO, NoExtensions, testDomain)
  private val managedGroupService = new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, policyDAO, dirDAO, NoExtensions, testDomain)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  def makeResourceType(resourceType: ResourceType): ResourceTypeName = resourceService.createResourceType(resourceType).unsafeRunSync()

  def assertPoliciesOnResource(resource: FullyQualifiedResourceId, policyDAO: AccessPolicyDAO = policyDAO, expectedPolicies: Set[AccessPolicyName] = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName)) = {
    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()
    policies.map(_.id.accessPolicyName.value) shouldEqual expectedPolicies.map(_.value)
    expectedPolicies.foreach { policyName =>
      val res = policyDAO.loadPolicy(FullyQualifiedPolicyId(resource, policyName)).unsafeRunSync()
      res.isInstanceOf[Some[AccessPolicy]] shouldBe true
    }
  }

  def assertMakeGroup(groupId: String = resourceId.value, managedGroupService: ManagedGroupService = managedGroupService, policyDAO: AccessPolicyDAO = policyDAO): Resource = {
    val resource: Resource = makeGroup(groupId, managedGroupService)
    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId), Set.empty)
    resource shouldEqual intendedResource
    assertPoliciesOnResource(resource.fullyQualifiedId, expectedPolicies = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName))
    resource
  }

  private def makeGroup(groupName: String, managedGroupService: ManagedGroupService, userInfo: UserInfo = dummyUserInfo) = {
    makeResourceType(managedGroupResourceType)
    runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), userInfo))
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)).unsafeRunSync()
  }

  "ManagedGroupService create" should "create a managed group with admin and member policies" in {
    assertMakeGroup()
    val policies = policyDAO.listAccessPolicies(expectedResource).unsafeRunSync()
    policies.map(_.id.accessPolicyName.value) shouldEqual Set("admin", "member", "admin-notifier")
  }

  it should "create a workbenchGroup with the same name as the Managed Group" in {
    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = dirDAO.loadGroup(WorkbenchGroupName(resourceId.value)).unsafeRunSync()
    samGroup.value.id.value shouldEqual resourceId.value
  }

  it should "create a workbenchGroup with 2 member WorkbenchSubjects" in {
    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = dirDAO.loadGroup(WorkbenchGroupName(resourceId.value)).unsafeRunSync()
    samGroup.value.members shouldEqual Set(adminPolicy, memberPolicy)
  }

  it should "sync the new group with Google" in {
    val mockGoogleExtensions = mock[GoogleExtensions]
    val managedGroupService = new ManagedGroupService(resourceService, null, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)
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
    managedGroupService.loadManagedGroup(resourceId).unsafeRunSync() shouldEqual None
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
    managedGroupService.loadManagedGroup(resourceId).unsafeRunSync() shouldEqual None
  }

  it should "fail when the group name has invalid characters" in {
    val groupName = "Make It Rain!!! $$$$$"
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include ("Group name may only contain alphanumeric characters, underscores, and dashes")
    managedGroupService.loadManagedGroup(resourceId).unsafeRunSync() shouldEqual None
  }

  "ManagedGroupService get" should "return the Managed Group resource" in {
    assertMakeGroup()
    val maybeEmail = managedGroupService.loadManagedGroup(resourceId).unsafeRunSync()
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
    val managedGroupService = new ManagedGroupService(resourceService, null, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)

    assertMakeGroup(managedGroupService = managedGroupService)
    runAndWait(managedGroupService.deleteManagedGroup(resourceId))
    verify(mockGoogleExtensions).onGroupDelete(groupEmail)
    policyDAO.listAccessPolicies(expectedResource).unsafeRunSync() shouldEqual Set.empty
    policyDAO.loadPolicy(adminPolicy).unsafeRunSync() shouldEqual None
    policyDAO.loadPolicy(memberPolicy).unsafeRunSync() shouldEqual None
  }

  it should "fail if the managed group is a sub group of any other workbench group" in {
    val managedGroup = assertMakeGroup("coolGroup")
    val managedGroupName = WorkbenchGroupName(managedGroup.resourceId.value)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(managedGroupName), WorkbenchEmail("foo@foo.gov"))

    dirDAO.createGroup(parentGroup).unsafeRunSync() shouldEqual parentGroup

    // using .get on an option here because if the Option is None and this throws an exception, that's fine
    dirDAO.loadGroup(parentGroup.id).unsafeRunSync().get.members shouldEqual Set(managedGroupName)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.deleteManagedGroup(managedGroup.resourceId))
    }

    managedGroupService.loadManagedGroup(managedGroup.resourceId).unsafeRunSync() shouldNot be (None)
    dirDAO.loadGroup(parentGroup.id).unsafeRunSync().get.members shouldEqual Set(managedGroupName)
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
    val dummyAdmin = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)
    val otherAdmin = WorkbenchUser(WorkbenchUserId("admin2"), None, WorkbenchEmail("admin2@foo.test"))
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    dirDAO.createUser(otherAdmin).unsafeRunSync()
    val managedGroup = assertMakeGroup()
    dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)).unsafeRunSync()

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
    val badAdmin = WorkbenchUser(WorkbenchUserId("admin2"), None, WorkbenchEmail("admin2@foo.test"))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, Set(badAdmin.email)))
    }
  }

  it should "permit overwriting the member policy" in {
    val managedGroup = assertMakeGroup()

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), None, WorkbenchEmail("someUser@foo.test"))
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    dirDAO.createUser(someUser).unsafeRunSync()
    dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail)).unsafeRunSync()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName)) shouldEqual Set()

    val newMembers = Set(someGroupEmail, someUser.email)
    runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, newMembers))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName)) shouldEqual newMembers
  }

  "ManagedGroupService addSubjectToPolicy" should "successfully add the subject to the existing policy for the group" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), None, WorkbenchEmail("someUser@foo.test"))
    dirDAO.createUser(someUser).unsafeRunSync()
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, someUser.id))

    val expectedEmails = Set(adminUser.email, someUser.email)
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual expectedEmails
  }

  it should "succeed without changing if the email address is already in the policy" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id))
    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)
  }

  // TODO: Is this right?  ResourceService.overwriteResource fails with invalid emails, should addSubjectToPolicy fail too?
  // The correct behavior is enforced in the routing, but is that the right place?  Should it be enforced in the Service class?
  it should "succeed even if the subject is doesn't exist" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    val someUser = WorkbenchUser(WorkbenchUserId("someUser"), None, WorkbenchEmail("someUser@foo.test"))
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, someUser.id))
  }

  "ManagedGroupService removeSubjectFromPolicy" should "successfully remove the subject from the policy for the group" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)

    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set(adminUser.email)

    runAndWait(managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id))

    runAndWait(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName)) shouldEqual Set.empty
  }

  it should "not do anything if the subject is not a member of the policy" in {
    val adminUser = WorkbenchUser(dummyUserInfo.userId, None, dummyUserInfo.userEmail)

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

    val resService = new ResourceService(resTypes, policyEvaluatorService, policyDAO, dirDAO, NoExtensions, testDomain)
    val mgService = new ManagedGroupService(resService, policyEvaluatorService, resTypes, policyDAO, dirDAO, NoExtensions, testDomain)

    val user1 = UserInfo(OAuth2BearerToken("token1"), WorkbenchUserId("userId1"), WorkbenchEmail("user1@company.com"), 0)
    val user2 = UserInfo(OAuth2BearerToken("token2"), WorkbenchUserId("userId2"), WorkbenchEmail("user2@company.com"), 0)
    dirDAO.createUser(WorkbenchUser(user1.userId, None, user1.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user2.userId, None, user2.userEmail)).unsafeRunSync()

    val user1Groups = Set("foo", "bar", "baz")
    val user2Groups = Set("qux", "quux")
    user1Groups.foreach(makeGroup(_, mgService, user1))
    user2Groups.foreach(makeGroup(_, mgService, user2))

    val user1Memberships = Set(user2Groups.head)
    val user2Memberships = Set(user1Groups.head)

    user1Memberships.foreach(s => runAndWait(mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user1.userId)))
    user2Memberships.foreach(s => runAndWait(mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user2.userId)))

    // let everyone notify admins
    (user1Groups ++ user2Groups).foreach { g =>
      runAndWait(mgService.addSubjectToPolicy(ResourceId(g), ManagedGroupService.adminNotifierPolicyName, user1.userId))
      runAndWait(mgService.addSubjectToPolicy(ResourceId(g), ManagedGroupService.adminNotifierPolicyName, user2.userId))
    }

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

  "ManagedGroupService getAccessInstructions" should "return access instructions when a group has them set" in {
    val managedGroup = assertMakeGroup()
    val instructions = "Test Instructions"

    managedGroupService.setAccessInstructions(managedGroup.resourceId, instructions).unsafeRunSync()
    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)).getOrElse(None) shouldEqual instructions
  }

  it should "return None when access instructions have not been set" in {
    val managedGroup = assertMakeGroup()

    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)) shouldEqual None
  }

  it should "throw an exception if the group is not found" in {
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.getAccessInstructions(ResourceId("Nonexistent Group")))
    }
    exception.getMessage should include ("not found")
  }

  "ManagedGroupService setAccessInstructions" should "set access instructions when a group has none" in {
    val managedGroup = assertMakeGroup()
    val instructions = "Test Instructions"

    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)) shouldEqual None
    managedGroupService.setAccessInstructions(managedGroup.resourceId, instructions).unsafeRunSync()
    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)).getOrElse(None) shouldEqual instructions
  }

  it should "modify the current access instructions" in {
    makeResourceType(managedGroupResourceType)

    val instructions = "Test Instructions"
    val managedGroup = runAndWait(managedGroupService.createManagedGroup(ResourceId(resourceId.value), dummyUserInfo, Option(instructions)))

    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)).getOrElse(None) shouldEqual instructions

    val newInstructions = "Much better instructions"
    managedGroupService.setAccessInstructions(managedGroup.resourceId, newInstructions).unsafeRunSync()
    runAndWait(managedGroupService.getAccessInstructions(managedGroup.resourceId)).getOrElse(None) shouldEqual newInstructions
  }

  "ManagedGroupService requestAccess" should "send notifications" in {
    val mockCloudExtension = mock[CloudExtensions]
    when(mockCloudExtension.publishGroup(ArgumentMatchers.any[WorkbenchGroupName])).thenReturn(Future.successful(()))
    val testManagedGroupService = new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, policyDAO, dirDAO, mockCloudExtension, testDomain)

    assertMakeGroup(groupId = resourceId.value, managedGroupService = testManagedGroupService)

    val requester = dirDAO.createUser(WorkbenchUser(WorkbenchUserId("userId1"), Some(GoogleSubjectId("this subject id is different that the user id")), WorkbenchEmail("user1@company.com"))).unsafeRunSync()
    val adminGoogleSubjectId = WorkbenchUserId(dirDAO.loadUser(dummyUserInfo.userId).unsafeRunSync().flatMap(_.googleSubjectId).getOrElse(fail("could not find admin google subject id")).value)

    val expectedNotificationMessages = Set(
      Notifications.GroupAccessRequestNotification(
        adminGoogleSubjectId,
        WorkbenchGroupName(resourceId.value).value,
        Set(adminGoogleSubjectId),
        requester.googleSubjectId.map(id => WorkbenchUserId(id.value)).getOrElse(fail("no requester google subject id"))
      ))

    testManagedGroupService.requestAccess(resourceId, requester.id).futureValue

    verify(mockCloudExtension).fireAndForgetNotifications(expectedNotificationMessages)
  }

  it should "throw an error if access instructions exist" in {
    assertMakeGroup(groupId = resourceId.value)
    managedGroupService.setAccessInstructions(resourceId, "instructions").unsafeRunSync()
    val error = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.requestAccess(resourceId, dummyUserInfo.userId))
    }
    error.errorReport.statusCode should be(Some(StatusCodes.BadRequest))
  }
}
