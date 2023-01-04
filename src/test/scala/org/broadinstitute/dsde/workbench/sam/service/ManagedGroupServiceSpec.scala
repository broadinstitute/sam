package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, PostgresAccessPolicyDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleCloudServices
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** Created by gpolumbo on 2/21/2018
  */
class ManagedGroupServiceSpec
    extends AnyFlatSpec
    with Matchers
    with TestSupport
    with MockitoSugar
    with BeforeAndAfter
    with BeforeAndAfterAll
    with ScalaFutures
    with OptionValues {
  // Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val resourceTypes = TestSupport.appConfig.resourceTypes
  private val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap

  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  lazy val policyDAO: AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)

  private val resourceId = ResourceId("myNewGroup")
  private val expectedResource = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId)
  private val adminPolicy = FullyQualifiedPolicyId(expectedResource, ManagedGroupService.adminPolicyName)
  private val memberPolicy = FullyQualifiedPolicyId(expectedResource, ManagedGroupService.memberPolicyName)

  private val managedGroupResourceType =
    resourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))
  private val testDomain = "example.com"

  private val policyEvaluatorService = PolicyEvaluatorService(testDomain, resourceTypeMap, policyDAO, dirDAO)
  private val resourceService = new ResourceService(resourceTypeMap, policyEvaluatorService, policyDAO, dirDAO, NoServices, testDomain, Set.empty)
  private val managedGroupService =
    new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, policyDAO, dirDAO, NoServices, testDomain)

  val dummyUser = Generator.genWorkbenchUserBoth.sample.get

  def makeResourceType(resourceType: ResourceType): ResourceType = resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync()

  def assertPoliciesOnResource(
      resource: FullyQualifiedResourceId,
      policyDAO: AccessPolicyDAO = policyDAO,
      expectedPolicies: LazyList[AccessPolicyName] = LazyList(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName)
  ) = {
    val policies = policyDAO.listAccessPolicies(resource, samRequestContext).unsafeRunSync()
    policies.map(_.id.accessPolicyName.value) should contain theSameElementsAs expectedPolicies.map(_.value)
    expectedPolicies.foreach { policyName =>
      val res = policyDAO.loadPolicy(FullyQualifiedPolicyId(resource, policyName), samRequestContext).unsafeRunSync()
      res.isInstanceOf[Some[AccessPolicy]] shouldBe true
    }
  }

  def assertMakeGroup(
      groupId: String = resourceId.value,
      managedGroupService: ManagedGroupService = managedGroupService,
      policyDAO: AccessPolicyDAO = policyDAO
  ): Resource = {
    val resource: Resource = makeGroup(groupId, managedGroupService)
    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId), Set.empty)

    // just compare top level fields because createResource returns the policies, including the default one
    resource.resourceTypeName shouldEqual intendedResource.resourceTypeName
    resource.resourceId shouldEqual intendedResource.resourceId

    assertPoliciesOnResource(
      resource.fullyQualifiedId,
      expectedPolicies = LazyList(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    )

    dirDAO.listFlattenedGroupMembers(WorkbenchGroupName(groupId), samRequestContext).unsafeRunSync() should contain theSameElementsAs (Set(dummyUser.id))

    resource
  }

  private def makeGroup(groupName: String, managedGroupService: ManagedGroupService, samUser: SamUser = dummyUser) = {
    makeResourceType(managedGroupResourceType)
    runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), samUser, samRequestContext = samRequestContext))
  }

  before {
    clearDatabase()
    if (databaseEnabled) {
      dirDAO.createUser(dummyUser, samRequestContext).unsafeRunSync()
    }
  }

  protected def clearDatabase(): Unit = TestSupport.truncateAll

  "ManagedGroupService create" should "create a managed group with admin and member policies" in {
    assume(databaseEnabled, databaseEnabledClue)

    assertMakeGroup()
    val policies = policyDAO.listAccessPolicies(expectedResource, samRequestContext).unsafeRunSync()
    policies.map(_.id.accessPolicyName.value) should contain theSameElementsAs Set("admin", "member", "admin-notifier")
  }

  it should "create a workbenchGroup with the same name as the Managed Group" in {
    assume(databaseEnabled, databaseEnabledClue)

    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = dirDAO.loadGroup(WorkbenchGroupName(resourceId.value), samRequestContext).unsafeRunSync()
    samGroup.value.id.value shouldEqual resourceId.value

  }

  it should "create a workbenchGroup with 2 member WorkbenchSubjects" in {
    assume(databaseEnabled, databaseEnabledClue)

    assertMakeGroup()
    val samGroup: Option[BasicWorkbenchGroup] = dirDAO.loadGroup(WorkbenchGroupName(resourceId.value), samRequestContext).unsafeRunSync()
    samGroup.value.members shouldEqual Set(adminPolicy, memberPolicy)
  }

  it should "sync the new group with Google" in {
    assume(databaseEnabled, databaseEnabledClue)

    val mockGoogleExtensions = mock[GoogleCloudServices](RETURNS_SMART_NULLS)
    val managedGroupService = new ManagedGroupService(resourceService, null, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)
    val groupName = WorkbenchGroupName(resourceId.value)

    when(mockGoogleExtensions.publishGroup(groupName)).thenReturn(Future.successful(()))
    assertMakeGroup(managedGroupService = managedGroupService)
    verify(mockGoogleExtensions).publishGroup(groupName)
  }

  it should "fail when trying to create a group that already exists" in {
    assume(databaseEnabled, databaseEnabledClue)

    val groupName = "uniqueName"
    assertMakeGroup(groupName)
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), dummyUser, samRequestContext = samRequestContext))
    }
    exception.getMessage should include("A resource of this type and name already exists")
    managedGroupService.loadManagedGroup(resourceId, samRequestContext).unsafeRunSync() shouldEqual None
  }

  it should "succeed after a managed group with the same name has been deleted" in {
    assume(databaseEnabled, databaseEnabledClue)

    val groupId = ResourceId("uniqueName")
    managedGroupResourceType.reuseIds shouldEqual true
    assertMakeGroup(groupId.value)
    runAndWait(managedGroupService.deleteManagedGroup(groupId, samRequestContext))
    assertMakeGroup(groupId.value)
  }

  it should "fail when the group name is too long" in {
    assume(databaseEnabled, databaseEnabledClue)

    val maxLen = 60
    val groupName = "a" * (maxLen + 1)
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include(s"must be $maxLen characters or fewer")
    managedGroupService.loadManagedGroup(resourceId, samRequestContext).unsafeRunSync() shouldEqual None
  }

  it should "fail when the group name has invalid characters" in {
    assume(databaseEnabled, databaseEnabledClue)

    val groupName = "Make It Rain!!! $$$$$"
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      assertMakeGroup(groupName)
    }
    exception.getMessage should include("Group name may only contain alphanumeric characters, underscores, and dashes")
    managedGroupService.loadManagedGroup(resourceId, samRequestContext).unsafeRunSync() shouldEqual None
  }

  "ManagedGroupService get" should "return the Managed Group resource" in {
    assume(databaseEnabled, databaseEnabledClue)

    assertMakeGroup()
    val maybeEmail = managedGroupService.loadManagedGroup(resourceId, samRequestContext).unsafeRunSync()
    maybeEmail.value.value shouldEqual s"${resourceId.value}@$testDomain"
  }

  "ManagedGroupService delete" should "delete policies associated with that resource in the database and in Google" in {
    assume(databaseEnabled, databaseEnabledClue)

    val groupEmail = WorkbenchEmail(resourceId.value + "@" + testDomain)
    val mockGoogleExtensions = mock[GoogleCloudServices](RETURNS_SMART_NULLS)
    when(mockGoogleExtensions.onGroupDelete(groupEmail)).thenReturn(Future.successful(()))
    when(mockGoogleExtensions.publishGroup(WorkbenchGroupName(resourceId.value))).thenReturn(Future.successful(()))
    val managedGroupService = new ManagedGroupService(resourceService, null, resourceTypeMap, policyDAO, dirDAO, mockGoogleExtensions, testDomain)

    assertMakeGroup(managedGroupService = managedGroupService)
    runAndWait(managedGroupService.deleteManagedGroup(resourceId, samRequestContext))
    verify(mockGoogleExtensions).onGroupDelete(groupEmail)
    policyDAO.listAccessPolicies(expectedResource, samRequestContext).unsafeRunSync() shouldEqual LazyList.empty
    policyDAO.loadPolicy(adminPolicy, samRequestContext).unsafeRunSync() shouldEqual None
    policyDAO.loadPolicy(memberPolicy, samRequestContext).unsafeRunSync() shouldEqual None
  }

  it should "fail if the managed group is a sub group of any other workbench group" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup("coolGroup")
    val managedGroupName = WorkbenchGroupName(managedGroup.resourceId.value)
    val parentGroup = BasicWorkbenchGroup(WorkbenchGroupName("parentGroup"), Set(managedGroupName), WorkbenchEmail("foo@foo.gov"))

    dirDAO.createGroup(parentGroup, samRequestContext = samRequestContext).unsafeRunSync() shouldEqual parentGroup

    // using .get on an option here because if the Option is None and this throws an exception, that's fine
    dirDAO.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().get.members shouldEqual Set(managedGroupName)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(managedGroupService.deleteManagedGroup(managedGroup.resourceId, samRequestContext))
    }

    managedGroupService.loadManagedGroup(managedGroup.resourceId, samRequestContext).unsafeRunSync() shouldNot be(None)
    dirDAO.loadGroup(parentGroup.id, samRequestContext).unsafeRunSync().get.members shouldEqual Set(managedGroupName)
  }

  "ManagedGroupService listPolicyMemberEmails" should "return a list of email addresses for the groups admin policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()
    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(dummyUser.email)
    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, samRequestContext)
      .unsafeRunSync() shouldEqual Set.empty
  }

  it should "throw an exception if the group does not exist" in {
    assume(databaseEnabled, databaseEnabledClue)

    intercept[WorkbenchExceptionWithErrorReport] {
      managedGroupService.listPolicyMemberEmails(resourceId, ManagedGroupService.adminPolicyName, samRequestContext).unsafeRunSync()
    }
  }

  "ManagedGroupService.overwritePolicyMemberEmails" should "permit overwriting the admin policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dummyAdmin = dummyUser
    val otherAdmin = Generator.genWorkbenchUserBoth.sample.get
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    dirDAO.createUser(otherAdmin, samRequestContext).unsafeRunSync()
    val managedGroup = assertMakeGroup()
    dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail), samRequestContext = samRequestContext).unsafeRunSync()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(dummyAdmin.email)

    runAndWait(
      managedGroupService.overwritePolicyMemberEmails(
        managedGroup.resourceId,
        ManagedGroupService.adminPolicyName,
        Set(otherAdmin.email, someGroupEmail),
        samRequestContext
      )
    )

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(otherAdmin.email, someGroupEmail)
    dirDAO.listFlattenedGroupMembers(WorkbenchGroupName(managedGroup.resourceId.value), samRequestContext).unsafeRunSync() should contain theSameElementsAs Set(
      otherAdmin.id
    )
  }

  it should "throw an exception if the group does not exist" in {
    assume(databaseEnabled, databaseEnabledClue)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(
        managedGroupService.overwritePolicyMemberEmails(expectedResource.resourceId, ManagedGroupService.adminPolicyName, Set.empty, samRequestContext)
      )
    }
  }

  it should "throw an exception if any of the email addresses do not match an existing subject" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(
        managedGroupService.overwritePolicyMemberEmails(
          managedGroup.resourceId,
          ManagedGroupService.adminPolicyName,
          Set(WorkbenchEmail("admin2@foo.test")),
          samRequestContext
        )
      )
    }
  }

  it should "permit overwriting the member policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()

    val someUser = Generator.genWorkbenchUserBoth.sample.get
    val someGroupEmail = WorkbenchEmail("someGroup@some.org")
    dirDAO.createUser(someUser, samRequestContext).unsafeRunSync()
    dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("someGroup"), Set.empty, someGroupEmail), samRequestContext = samRequestContext).unsafeRunSync()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set()

    val newMembers = Set(someGroupEmail, someUser.email)
    runAndWait(managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, newMembers, samRequestContext))

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.memberPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs newMembers
  }

  "ManagedGroupService addSubjectToPolicy" should "successfully add the subject to the existing policy for the group" in {
    assume(databaseEnabled, databaseEnabledClue)

    val adminUser = dummyUser

    val managedGroup = assertMakeGroup()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)

    val someUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(someUser, samRequestContext).unsafeRunSync()
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, someUser.id, samRequestContext))

    val expectedEmails = Set(adminUser.email, someUser.email)
    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs expectedEmails
  }

  it should "succeed without changing if the email address is already in the policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val adminUser = dummyUser

    val managedGroup = assertMakeGroup()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)
    runAndWait(managedGroupService.addSubjectToPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id, samRequestContext))
    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)
  }

  "ManagedGroupService removeSubjectFromPolicy" should "successfully remove the subject from the policy for the group" in {
    assume(databaseEnabled, databaseEnabledClue)

    val adminUser = dummyUser

    val managedGroup = assertMakeGroup()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)

    runAndWait(managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, adminUser.id, samRequestContext))

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set.empty
  }

  it should "not do anything if the subject is not a member of the policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val adminUser = dummyUser

    val managedGroup = assertMakeGroup()

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)

    runAndWait(
      managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, ManagedGroupService.adminPolicyName, WorkbenchUserId("someUser"), samRequestContext)
    )

    managedGroupService
      .listPolicyMemberEmails(managedGroup.resourceId, ManagedGroupService.adminPolicyName, samRequestContext)
      .unsafeRunSync() should contain theSameElementsAs Set(adminUser.email)
  }

  private def makeResource(resourceType: ResourceType, resourceId: ResourceId, samUser: SamUser): Resource = runAndWait(
    resourceService.createResource(resourceType, resourceId, samUser, samRequestContext)
  )

  "ManagedGroupService listGroups" should "return the list of groups that passed user belongs to" in {
    assume(databaseEnabled, databaseEnabledClue)

    // Setup multiple managed groups owned by different users.
    // Make the different users a member of some of the groups owned by the other user
    // Create some resources owned by different users
    // List Managed Group memberships for users and assert that memberships are only returned for managed groups
    val newResourceType = managedGroupResourceType.copy(name = ResourceTypeName(UUID.randomUUID().toString))
    makeResourceType(newResourceType)
    val resTypes = resourceTypeMap + (newResourceType.name -> newResourceType)

    val resService = new ResourceService(resTypes, policyEvaluatorService, policyDAO, dirDAO, NoServices, testDomain, Set.empty)
    val mgService = new ManagedGroupService(resService, policyEvaluatorService, resTypes, policyDAO, dirDAO, NoServices, testDomain)

    val user1 = Generator.genWorkbenchUserBoth.sample.get
    val user2 = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(user1, samRequestContext).unsafeRunSync()
    dirDAO.createUser(user2, samRequestContext).unsafeRunSync()

    val user1Groups = Set("foo", "bar", "baz")
    val user2Groups = Set("qux", "quux")
    user1Groups.foreach(makeGroup(_, mgService, user1))
    user2Groups.foreach(makeGroup(_, mgService, user2))

    val user1Memberships = Set(user2Groups.head)
    val user2Memberships = Set(user1Groups.head)

    user1Memberships.foreach(s => runAndWait(mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user1.id, samRequestContext)))
    user2Memberships.foreach(s => runAndWait(mgService.addSubjectToPolicy(ResourceId(s), ManagedGroupService.memberPolicyName, user2.id, samRequestContext)))

    // let everyone notify admins
    (user1Groups ++ user2Groups).foreach { g =>
      runAndWait(mgService.addSubjectToPolicy(ResourceId(g), ManagedGroupService.adminNotifierPolicyName, user1.id, samRequestContext))
      runAndWait(mgService.addSubjectToPolicy(ResourceId(g), ManagedGroupService.adminNotifierPolicyName, user2.id, samRequestContext))
    }

    val user1Resources = Set("quuz", "corge")
    val user2Resources = Set("grault", "garply")

    user1Resources.foreach(s => makeResource(newResourceType, ResourceId(s), user1))
    user2Resources.foreach(s => makeResource(newResourceType, ResourceId(s), user2))

    val user1ExpectedAdmin =
      user1Groups.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.adminRoleName, WorkbenchEmail(s"$s@example.com")))
    val user1ExpectedMember =
      user1Memberships.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.memberRoleName, WorkbenchEmail(s"$s@example.com")))
    val user1ExpectedGroups = user1ExpectedAdmin ++ user1ExpectedMember
    mgService.listGroups(user1.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs user1ExpectedGroups

    val user2ExpectedAdmin =
      user2Groups.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.adminRoleName, WorkbenchEmail(s"$s@example.com")))
    val user2ExpectedMember =
      user2Memberships.map(s => ManagedGroupMembershipEntry(ResourceId(s), ManagedGroupService.memberRoleName, WorkbenchEmail(s"$s@example.com")))
    val user2ExpectedGroups = user2ExpectedAdmin ++ user2ExpectedMember
    mgService.listGroups(user2.id, samRequestContext).unsafeRunSync() should contain theSameElementsAs user2ExpectedGroups
  }

  "ManagedGroupService getAccessInstructions" should "return access instructions when a group has them set" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()
    val instructions = "Test Instructions"

    managedGroupService.setAccessInstructions(managedGroup.resourceId, instructions, samRequestContext).unsafeRunSync()
    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync().getOrElse(None) shouldEqual instructions
  }

  it should "return None when access instructions have not been set" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()

    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync() shouldEqual None
  }

  it should "throw an exception if the group is not found" in {
    assume(databaseEnabled, databaseEnabledClue)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      managedGroupService.getAccessInstructions(ResourceId("Nonexistent Group"), samRequestContext).unsafeRunSync()
    }
    exception.getMessage should include("not found")
  }

  "ManagedGroupService setAccessInstructions" should "set access instructions when a group has none" in {
    assume(databaseEnabled, databaseEnabledClue)

    val managedGroup = assertMakeGroup()
    val instructions = "Test Instructions"

    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync() shouldEqual None
    managedGroupService.setAccessInstructions(managedGroup.resourceId, instructions, samRequestContext).unsafeRunSync()
    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync().getOrElse(None) shouldEqual instructions
  }

  it should "modify the current access instructions" in {
    assume(databaseEnabled, databaseEnabledClue)

    makeResourceType(managedGroupResourceType)

    val instructions = "Test Instructions"
    val managedGroup = runAndWait(managedGroupService.createManagedGroup(ResourceId(resourceId.value), dummyUser, Option(instructions), samRequestContext))

    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync().getOrElse(None) shouldEqual instructions

    val newInstructions = "Much better instructions"
    managedGroupService.setAccessInstructions(managedGroup.resourceId, newInstructions, samRequestContext).unsafeRunSync()
    managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).unsafeRunSync().getOrElse(None) shouldEqual newInstructions
  }

  "ManagedGroupService requestAccess" should "send notifications" in {
    assume(databaseEnabled, databaseEnabledClue)

    val mockCloudExtension = mock[CloudServices](RETURNS_SMART_NULLS)
    when(mockCloudExtension.publishGroup(ArgumentMatchers.any[WorkbenchGroupName])).thenReturn(Future.successful(()))
    val testManagedGroupService =
      new ManagedGroupService(resourceService, policyEvaluatorService, resourceTypeMap, policyDAO, dirDAO, mockCloudExtension, testDomain)

    assertMakeGroup(groupId = resourceId.value, managedGroupService = testManagedGroupService)

    val requester = dirDAO.createUser(Generator.genWorkbenchUserBoth.sample.get, samRequestContext).unsafeRunSync()
    val adminGoogleSubjectId = WorkbenchUserId(
      dirDAO
        .loadUser(dummyUser.id, samRequestContext)
        .unsafeRunSync()
        .flatMap(_.googleSubjectId)
        .getOrElse(fail("could not find admin google subject id"))
        .value
    )

    val expectedNotificationMessages = Set(
      Notifications.GroupAccessRequestNotification(
        adminGoogleSubjectId,
        WorkbenchGroupName(resourceId.value).value,
        Set(adminGoogleSubjectId),
        requester.googleSubjectId.map(id => WorkbenchUserId(id.value)).getOrElse(fail("no requester google subject id"))
      )
    )

    testManagedGroupService.requestAccess(resourceId, requester.id, samRequestContext).unsafeRunSync()

    verify(mockCloudExtension).fireAndForgetNotifications(expectedNotificationMessages)
  }

  it should "throw an error if access instructions exist" in {
    assume(databaseEnabled, databaseEnabledClue)

    assertMakeGroup(groupId = resourceId.value)
    managedGroupService.setAccessInstructions(resourceId, "instructions", samRequestContext).unsafeRunSync()
    val error = intercept[WorkbenchExceptionWithErrorReport] {
      managedGroupService.requestAccess(resourceId, dummyUser.id, samRequestContext).unsafeRunSync()
    }
    error.errorReport.statusCode should be(Some(StatusCodes.BadRequest))
  }
}
