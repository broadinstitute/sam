package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.google.api.client.http.{HttpHeaders, HttpResponseException}
import com.google.api.services.cloudresourcemanager.model.Ancestor
import com.google.api.services.groupssettings.model.Groups
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.MessageRequest
import org.broadinstitute.dsde.workbench.google.mock._
import org.broadinstitute.dsde.workbench.google2.GcsBlobName
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.model.Notifications.NotificationFormat
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue, truncateAll}
import org.broadinstitute.dsde.workbench.sam.dataAccess._
import org.broadinstitute.dsde.workbench.sam.mock.RealKeyMockGoogleIamDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model, _}
import org.mockito.ArgumentMatcher
import org.mockito.Mockito._
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, PrivateMethodTester}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.{Date, GregorianCalendar, UUID}
import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import scala.concurrent.Future
import scala.concurrent.duration._

class GoogleExtensionSpec(_system: ActorSystem)
    extends TestKit(_system)
    with AnyFlatSpecLike
    with Matchers
    with TestSupport
    with MockitoSugar
    with ScalaFutures
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with PrivateMethodTester {
  def this() = this(ActorSystem("GoogleGroupSyncMonitorSpec"))

  override def beforeAll(): Unit =
    super.beforeAll()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override def beforeEach(): Unit =
    truncateAll

  lazy val petServiceAccountConfig = TestSupport.petServiceAccountConfig
  lazy val googleServicesConfig = TestSupport.googleServicesConfig
  lazy val superAdminsGroup = TestSupport.adminConfig.superAdminsGroup

  val configResourceTypes = TestSupport.configResourceTypes
  override implicit val patienceConfig = PatienceConfig(5 seconds)
  "Google group sync" should "add/remove the right emails and handle errors" in {
    // tests that emails only in sam get added to google
    // emails only in google are removed
    // emails in both are neither added or removed
    // errors adding/removing to/from google are reported

    val groupName = WorkbenchGroupName("group1")
    val groupEmail = WorkbenchEmail("group1@example.com")
    val inSamSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inSamSubGroup"), Set.empty, WorkbenchEmail("inSamSubGroup@example.com"))
    val inGoogleSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inGoogleSubGroup"), Set.empty, WorkbenchEmail("inGoogleSubGroup@example.com"))
    val inBothSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set.empty, WorkbenchEmail("inBothSubGroup@example.com"))

    val inSamUserId = WorkbenchUserId("inSamUser")
    val inSamUserProxyEmail = s"PROXY_inSamUser@${googleServicesConfig.appsDomain}"

    val inGoogleUserId = WorkbenchUserId("inGoogleUser")
    val inGoogleUserProxyEmail = s"PROXY_inGoogleUser@${googleServicesConfig.appsDomain}"

    val inBothUserId = WorkbenchUserId("inBothUser")
    val inBothUserProxyEmail = s"PROXY_inBothUser@${googleServicesConfig.appsDomain}"

    val addError = WorkbenchUserId("addError")
    val addErrorProxyEmail = s"PROXY_addError@${googleServicesConfig.appsDomain}"

    val removeError = "removeError@foo.bar"

    val testGroup = BasicWorkbenchGroup(groupName, Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail)
    val testPolicy = AccessPolicy(
      model.FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("workspace"), ResourceId("rid")), AccessPolicyName("ap")),
      Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError),
      groupEmail,
      Set.empty,
      Set.empty,
      Set.empty,
      public = true
    )

    Seq(testGroup, testPolicy).foreach { target =>
      val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
      val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
      val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
      val mockGoogleNotificationsPubSubDAO = new MockGooglePubSubDAO
      val mockGoogleGroupSyncPubSubDAO = new MockGooglePubSubDAO
      val mockGoogleDisableUsersPubSubDAO = new MockGooglePubSubDAO
      val ge = new GoogleExtensions(
        TestSupport.distributedLock,
        mockDirectoryDAO,
        mockAccessPolicyDAO,
        mockGoogleDirectoryDAO,
        mockGoogleNotificationsPubSubDAO,
        mockGoogleGroupSyncPubSubDAO,
        mockGoogleDisableUsersPubSubDAO,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        googleServicesConfig,
        petServiceAccountConfig,
        configResourceTypes,
        superAdminsGroup
      )
      val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, ge, configResourceTypes)

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id, samRequestContext)).thenReturn(IO.pure(Option(testGroup)))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id, samRequestContext)).thenReturn(IO.pure(Option(testPolicy)))
      }
      when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.unit)
      when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext]))
        .thenReturn(IO.pure(Some(new GregorianCalendar(2017, 11, 22).getTime())))

      when(mockDirectoryDAO.loadSubjectEmail(inSamSubGroup.id, samRequestContext)).thenReturn(IO.pure(Option(inSamSubGroup.email)))
      when(mockDirectoryDAO.loadSubjectEmail(inBothSubGroup.id, samRequestContext)).thenReturn(IO.pure(Option(inBothSubGroup.email)))
      // mockito calls this next stubbing unnecessary but it isn't... shrug
      lenient().when(mockDirectoryDAO.loadSubjectEmail(CloudExtensions.allUsersGroupName, samRequestContext)).thenReturn(IO.pure(Option(ge.allUsersGroupEmail)))

      val added = Seq(inSamSubGroup.email, WorkbenchEmail(inSamUserProxyEmail)) ++ (target match {
        case _: BasicWorkbenchGroup => Seq.empty
        case _: AccessPolicy => Set(ge.allUsersGroupEmail)
      })

      val removed = Seq(inGoogleSubGroup.email, WorkbenchEmail(inGoogleUserProxyEmail))

      when(mockGoogleDirectoryDAO.listGroupMembers(target.email)).thenReturn(
        Future.successful(
          Option(
            Seq(
              WorkbenchEmail(inGoogleUserProxyEmail).value,
              WorkbenchEmail(inBothUserProxyEmail).value.toLowerCase,
              inGoogleSubGroup.email.value,
              inBothSubGroup.email.value,
              removeError
            )
          )
        )
      )
      when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

      val addException = new Exception("addError")
      when(mockGoogleDirectoryDAO.addMemberToGroup(target.email, WorkbenchEmail(addErrorProxyEmail.toLowerCase))).thenReturn(Future.failed(addException))

      val removeException = new Exception("removeError")
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(target.email, WorkbenchEmail(removeError.toLowerCase))).thenReturn(Future.failed(removeException))

      val results = runAndWait(synchronizer.synchronizeGroupMembers(target.id, samRequestContext = samRequestContext))

      results.head._1 should equal(target.email)
      results.head._2 should contain theSameElementsAs (added.map(e => SyncReportItem("added", e.value.toLowerCase, target.id.toString, None)) ++
        removed.map(e => SyncReportItem("removed", e.value.toLowerCase, target.id.toString, None)) ++
        Seq(
          SyncReportItem("added", addErrorProxyEmail.toLowerCase, target.id.toString, Option(ErrorReport(addException))),
          SyncReportItem("removed", removeError.toLowerCase, target.id.toString, Option(ErrorReport(removeException)))
        ))

      added.foreach(email => verify(mockGoogleDirectoryDAO).addMemberToGroup(target.email, WorkbenchEmail(email.value.toLowerCase)))
      removed.foreach(email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(target.email, WorkbenchEmail(email.value.toLowerCase)))
      verify(mockDirectoryDAO).updateSynchronizedDate(target.id, samRequestContext)
    }
  }

  it should "sync the intersection group if the policy is constrainable" in {
    // In both the policy and the auth domain, will be added to Google Group
    val intersectionSamUserId = WorkbenchUserId("intersectionSamUser")
    val intersectionSamUserProxyEmail = s"PROXY_intersectionSamUser@${googleServicesConfig.appsDomain}"

    // In only the policy, not the auth domain, will not be synced
    val policyOnlySamUserId = WorkbenchUserId("policyOnlySamUser")

    // Currently synced with Google, but in neither the policy nor the auth domain, so will be removed from Google Group
    val unauthorizedGoogleUserProxyEmail = s"PROXY_unauthorizedGoogleUser@${googleServicesConfig.appsDomain}"

    // Currently synced with Google and in policy and auth domain, will remain in Google Group
    val authorizedGoogleUserId = WorkbenchUserId("authorizedGoogleUser")
    val authorizedGoogleUserProxyEmail = s"PROXY_authorizedGoogleUser@${googleServicesConfig.appsDomain}"

    val addError = WorkbenchUserId("addError")
    val addErrorProxyEmail = s"PROXY_addError@${googleServicesConfig.appsDomain}"

    val removeError = "removeError@foo.bar"

    val subPolicyOnlySamGroupUserId = WorkbenchUserId("policySamSubUser")

    val subIntersectionSamGroupUserId = WorkbenchUserId("intersectionSamSubUser")
    val subIntersectionSamGroupUserProxyEmail = s"PROXY_intersectionSamSubUser@${googleServicesConfig.appsDomain}"

    val subAuthorizedGoogleGroupUserId = WorkbenchUserId("authorizedGoogleSubUser")
    val subAuthorizedGoogleGroupUserProxyEmail = s"PROXY_authorizedGoogleSubUser@${googleServicesConfig.appsDomain}"

    val policyOnlySamSubGroup =
      BasicWorkbenchGroup(WorkbenchGroupName("inSamSubGroup"), Set(subPolicyOnlySamGroupUserId), WorkbenchEmail("inSamSubGroup@example.com"))
    val intersectionSamSubGroup =
      BasicWorkbenchGroup(WorkbenchGroupName("inGoogleSubGroup"), Set(subIntersectionSamGroupUserId), WorkbenchEmail("inGoogleSubGroup@example.com"))
    val authorizedGoogleSubGroup =
      BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set(subAuthorizedGoogleGroupUserId), WorkbenchEmail("inBothSubGroup@example.com"))

    // Set up constrainable resource type
    val constrainableActionPatterns = Set(ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true))
    val constrainableViewAction = ResourceAction("constrainable_view")
    val constrainableResourceTypeActions = Set(constrainableViewAction)
    val constrainableReaderRoleName = ResourceRoleName("constrainable_reader")
    val constrainableRole = ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)
    val constrainableResourceType = ResourceType(
      ResourceTypeName(UUID.randomUUID().toString),
      constrainableActionPatterns,
      Set(ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)),
      constrainableReaderRoleName
    )
    val constrainableResourceTypes = Map(constrainableResourceType.name -> constrainableResourceType)

    // Set up constrained policy
    val managedGroupId = WorkbenchGroupName("authDomainGroup")
    val resource = Resource(constrainableResourceType.name, ResourceId("rid"), Set(managedGroupId))
    val rpn = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("ap"))
    val testPolicy = AccessPolicy(
      rpn,
      Set(
        policyOnlySamUserId,
        intersectionSamUserId,
        authorizedGoogleUserId,
        policyOnlySamSubGroup.id,
        intersectionSamSubGroup.id,
        authorizedGoogleSubGroup.id,
        addError
      ),
      WorkbenchEmail("testPolicy@example.com"),
      Set(constrainableRole.roleName),
      constrainableRole.actions,
      Set.empty,
      public = false
    )

    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      constrainableResourceTypes,
      superAdminsGroup
    )
    val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, ge, constrainableResourceTypes)

    when(mockAccessPolicyDAO.loadPolicy(testPolicy.id, samRequestContext)).thenReturn(IO.pure(Option(testPolicy)))
    when(mockAccessPolicyDAO.loadResourceAuthDomain(resource.fullyQualifiedId, samRequestContext))
      .thenReturn(IO.pure(LoadResourceAuthDomainResult.Constrained(NonEmptyList.one(managedGroupId)): LoadResourceAuthDomainResult))

    when(mockDirectoryDAO.listIntersectionGroupUsers(Set(managedGroupId, testPolicy.id), samRequestContext))
      .thenReturn(IO.pure(Set(intersectionSamUserId, authorizedGoogleUserId, subIntersectionSamGroupUserId, subAuthorizedGoogleGroupUserId, addError)))

    when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.unit)
    when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext]))
      .thenReturn(IO.pure(Some(new GregorianCalendar(2017, 11, 22).getTime())))

    val added = Seq(WorkbenchEmail(intersectionSamUserProxyEmail), WorkbenchEmail(subIntersectionSamGroupUserProxyEmail))
    val removed = Seq(WorkbenchEmail(unauthorizedGoogleUserProxyEmail))

    // mock pre-sync google group members
    when(mockGoogleDirectoryDAO.listGroupMembers(testPolicy.email)).thenReturn(
      Future.successful(Option(Seq(authorizedGoogleUserProxyEmail, unauthorizedGoogleUserProxyEmail, subAuthorizedGoogleGroupUserProxyEmail, removeError)))
    )
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    val addException = new Exception("addError")
    when(mockGoogleDirectoryDAO.addMemberToGroup(testPolicy.email, WorkbenchEmail(addErrorProxyEmail.toLowerCase))).thenReturn(Future.failed(addException))

    val removeException = new Exception("removeError")
    when(mockGoogleDirectoryDAO.removeMemberFromGroup(testPolicy.email, WorkbenchEmail(removeError.toLowerCase))).thenReturn(Future.failed(removeException))

    val results = runAndWait(synchronizer.synchronizeGroupMembers(testPolicy.id, samRequestContext = samRequestContext))

    results.head._1 should equal(testPolicy.email)
    results.head._2 should contain theSameElementsAs (added.map(e => SyncReportItem("added", e.value.toLowerCase, testPolicy.id.toString, None)) ++
      removed.map(e => SyncReportItem("removed", e.value.toLowerCase, testPolicy.id.toString, None)) ++
      Seq(
        SyncReportItem("added", addErrorProxyEmail.toLowerCase, testPolicy.id.toString, Option(ErrorReport(addException))),
        SyncReportItem("removed", removeError.toLowerCase, testPolicy.id.toString, Option(ErrorReport(removeException)))
      ))

    added.foreach(email => verify(mockGoogleDirectoryDAO).addMemberToGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)))
    removed.foreach(email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)))
    verify(mockDirectoryDAO).updateSynchronizedDate(testPolicy.id, samRequestContext)
  }

  it should "break out of cycle" in {
    val groupName = WorkbenchGroupName("group1")
    val groupEmail = WorkbenchEmail("group1@example.com")
    val subGroupName = WorkbenchGroupName("group2")
    val subGroupEmail = WorkbenchEmail("group2@example.com")

    val subGroup = BasicWorkbenchGroup(subGroupName, Set.empty, subGroupEmail)
    val topGroup = BasicWorkbenchGroup(groupName, Set.empty, groupEmail)

    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleNotificationsPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleGroupSyncPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleDisableUsersPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      mockGoogleDirectoryDAO,
      mockGoogleNotificationsPubSubDAO,
      mockGoogleGroupSyncPubSubDAO,
      mockGoogleDisableUsersPubSubDAO,
      mockGoogleIamDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )
    val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, ge, configResourceTypes)

    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    // create groups
    mockDirectoryDAO.createGroup(topGroup, samRequestContext = samRequestContext).unsafeRunSync()
    mockDirectoryDAO.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
    // add subGroup to topGroup
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(groupEmail, subGroupEmail))
    mockDirectoryDAO.addGroupMember(topGroup.id, subGroup.id, samRequestContext).unsafeRunSync()
    // add topGroup to subGroup - creating cycle
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(subGroupEmail, groupEmail))
    mockDirectoryDAO.addGroupMember(subGroup.id, topGroup.id, samRequestContext).unsafeRunSync()
    when(mockGoogleDirectoryDAO.listGroupMembers(topGroup.email)).thenReturn(Future.successful(Option(Seq(subGroupEmail.value))))
    when(mockGoogleDirectoryDAO.listGroupMembers(subGroup.email)).thenReturn(Future.successful(Option(Seq(groupEmail.value))))
    val syncedEmails = runAndWait(synchronizer.synchronizeGroupMembers(topGroup.id, samRequestContext = samRequestContext)).keys
    syncedEmails shouldEqual Set(groupEmail, subGroupEmail)
  }

  "GoogleExtension" should "get a pet service account for a user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      tosService: TosService,
      mockGoogleIamDAO: MockGoogleIamDAO,
      mockGoogleDirectoryDAO: MockGoogleDirectoryDAO,
      googleExtensions: GoogleExtensions,
      service: UserService,
      defaultUserProxyEmail: WorkbenchEmail,
      defaultUser: SamUser
    ) = initPetTest

    // create a user
    val newUser = newUserWithAcceptedTos(service, tosService, defaultUser, samRequestContext)
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), TestSupport.enabledMapTosAccepted)

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    petServiceAccount.serviceAccount.email.value should endWith(s"@${googleProject.value}.iam.gserviceaccount.com")

    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUser.id, googleProject), samRequestContext).unsafeRunSync() shouldBe Some(petServiceAccount)

    // verify google
    mockGoogleIamDAO.serviceAccounts should contain key petServiceAccount.serviceAccount.email
    mockGoogleDirectoryDAO.groups should contain key defaultUserProxyEmail
    mockGoogleDirectoryDAO.groups(defaultUserProxyEmail) shouldBe Set(defaultUser.email, petServiceAccount.serviceAccount.email)

    // create one again, it should work
    val petSaResponse2 = googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petSaResponse2 shouldBe petServiceAccount

    // delete the pet service account
    googleExtensions.deleteUserPetServiceAccount(newUser.userInfo.userSubjectId, googleProject, samRequestContext).unsafeRunSync() shouldBe true

    // the user should still exist in DB
    dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.copy(enabled = true))

    // the pet should not exist in DB
    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUser.id, googleProject), samRequestContext).unsafeRunSync() shouldBe None

    // the pet should not exist in Google
    mockGoogleIamDAO.serviceAccounts should not contain key(petServiceAccount.serviceAccount.email)

  }

  private def initPetTest: (DirectoryDAO, TosService, MockGoogleIamDAO, MockGoogleDirectoryDAO, GoogleExtensions, UserService, WorkbenchEmail, SamUser) = {
    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO

    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      mockGoogleIamDAO,
      null,
      mockGoogleProjectDAO,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )
    val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)
    val service = new UserService(dirDAO, googleExtensions, Seq.empty, tosService)

    val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
    val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_${defaultUser.id}@${googleServicesConfig.appsDomain}")
    (dirDAO, tosService, mockGoogleIamDAO, mockGoogleDirectoryDAO, googleExtensions, service, defaultUserProxyEmail, defaultUser)
  }

  def newUserWithAcceptedTos(userService: UserService, tosService: TosService, samUser: SamUser, samRequestContext: SamRequestContext): UserStatus = {
    TestSupport.runAndWait(userService.createUser(samUser, samRequestContext))
    TestSupport.runAndWait(tosService.acceptCurrentTermsOfService(samUser.id, samRequestContext))
    TestSupport.runAndWait(userService.getUserStatus(samUser.id, samRequestContext = samRequestContext)).orNull
  }

  protected def newDirectoryDAO(): DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  protected def newAccessPolicyDAO(): AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)

  it should "attach existing service account to pet" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      tosService: TosService,
      mockGoogleIamDAO: MockGoogleIamDAO,
      mockGoogleDirectoryDAO: MockGoogleDirectoryDAO,
      googleExtensions: GoogleExtensions,
      service: UserService,
      defaultUserProxyEmail: WorkbenchEmail,
      defaultUser: SamUser
    ) = initPetTest
    val googleProject = GoogleProject("testproject")

    val (saName, saDisplayName) = googleExtensions.petServiceAccounts.toPetSAFromUser(defaultUser)
    val serviceAccount = mockGoogleIamDAO.createServiceAccount(googleProject, saName, saDisplayName).futureValue
    // create a user

    val newUser = newUserWithAcceptedTos(service, tosService, defaultUser, samRequestContext)
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), TestSupport.enabledMapTosAccepted)

    // create a pet service account
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petServiceAccount.serviceAccount shouldBe serviceAccount

  }

  it should "recreate service account when missing for pet" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      tosService: TosService,
      mockGoogleIamDAO: MockGoogleIamDAO,
      mockGoogleDirectoryDAO: MockGoogleDirectoryDAO,
      googleExtensions: GoogleExtensions,
      service: UserService,
      defaultUserProxyEmail: WorkbenchEmail,
      defaultUser: SamUser
    ) = initPetTest

    // create a user
    val newUser = newUserWithAcceptedTos(service, tosService, defaultUser, samRequestContext)
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), TestSupport.enabledMapTosAccepted)

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    import org.broadinstitute.dsde.workbench.model.google.toAccountName
    mockGoogleIamDAO.removeServiceAccount(googleProject, toAccountName(petServiceAccount.serviceAccount.email)).futureValue
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe None

    val petServiceAccount2 = googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petServiceAccount.serviceAccount shouldNot equal(petServiceAccount2.serviceAccount)
    val res = dirDAO.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(petServiceAccount2)
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe Some(petServiceAccount2.serviceAccount)
  }

  it should "get a group's last synchronized date" in {
    val groupName = WorkbenchGroupName("group1")

    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )

    when(mockDirectoryDAO.getSynchronizedDate(groupName, samRequestContext)).thenReturn(IO.pure(None))
    ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe None

    val date = new Date()
    when(mockDirectoryDAO.getSynchronizedDate(groupName, samRequestContext)).thenReturn(IO.pure(Some(date)))
    ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe Some(date)
  }

  it should "throw an exception with a NotFound error report when getting sync date for group that does not exist" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync()
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include(groupName.toString)
  }

  it should "return None when getting sync date for a group that has not been synced" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val groupName = WorkbenchGroupName("group-sync")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail("")), samRequestContext = samRequestContext).unsafeRunSync()
    try
      ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe None
    finally
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
  }

  it should "return sync date for a group that has been synced" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO()
    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      new MockGoogleDirectoryDAO(),
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val synchronizer = new GoogleGroupSynchronizer(dirDAO, null, mockGoogleDirectoryDAO, ge, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    dirDAO
      .createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail("group1@test.firecloud.org")), samRequestContext = samRequestContext)
      .unsafeRunSync()
    try {
      runAndWait(synchronizer.synchronizeGroupMembers(groupName, samRequestContext = samRequestContext))
      val syncDate = ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync().get
      syncDate.getTime should equal(new Date().getTime +- 1.second.toMillis)
    } finally
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
  }

  it should "throw an exception with a NotFound error report when getting email for group that does not exist" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      ge.getSynchronizedEmail(groupName, samRequestContext).unsafeRunSync()
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include(groupName.toString)
  }

  it should "return email for a group" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try
      ge.getSynchronizedEmail(groupName, samRequestContext).unsafeRunSync() shouldBe Some(email)
    finally
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
  }

  it should "return None if an email is found, but the group has not been synced" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try
      ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync() shouldBe None
    finally
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
  }

  it should "return SyncState with email and last sync date if there is an email and the group has been synced" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO()
    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )
    val synchronizer = new GoogleGroupSynchronizer(dirDAO, null, mockGoogleDirectoryDAO, ge, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync() should equal(None)
      runAndWait(synchronizer.synchronizeGroupMembers(groupName, samRequestContext = samRequestContext))
      val maybeSyncResponse = ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync()
      maybeSyncResponse.map(_.email) should equal(Some(email))
    } finally
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
  }

  it should "create google extension resource on boot" in {
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockAccessPolicyDAO = new MockAccessPolicyDAO(mockDirectoryDAO)
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleKeyCachePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleGroupSyncPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleDisableUsersPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(
      TestSupport.distributedLock,
      mockGoogleIamDAO,
      mockGoogleStorageDAO,
      FakeGoogleStorageInterpreter,
      mockGoogleKeyCachePubSubDAO,
      googleServicesConfig,
      petServiceAccountConfig
    ) {
      // don't do any of the real boot stuff, it is all googley
      override def onBoot()(implicit system: ActorSystem): IO[Unit] = IO.unit
    }

    val ge = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      mockGoogleDirectoryDAO,
      mockGoogleNotificationPubSubDAO,
      mockGoogleGroupSyncPubSubDAO,
      mockGoogleDisableUsersPubSubDAO,
      mockGoogleIamDAO,
      mockGoogleStorageDAO,
      null,
      googleKeyCache,
      notificationDAO,
      FakeGoogleKmsInterpreter,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )

    val app = SamApplication(
      new UserService(mockDirectoryDAO, ge, Seq.empty, new TosService(NoExtensions, mockDirectoryDAO, TestSupport.tosConfig)),
      new ResourceService(configResourceTypes, null, mockAccessPolicyDAO, mockDirectoryDAO, ge, "example.com", Set.empty),
      null,
      new TosService(NoExtensions, mockDirectoryDAO, TestSupport.tosConfig)
    )
    val resourceAndPolicyName =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), AccessPolicyName("owner"))

    mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId), samRequestContext).unsafeRunSync() shouldBe None
    mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync() shouldBe None

    ge.onBoot(app).unsafeRunSync()

    val uid = mockDirectoryDAO
      .loadSubjectFromGoogleSubjectId(GoogleSubjectId(googleServicesConfig.serviceAccountClientId), samRequestContext)
      .unsafeRunSync()
      .get
      .asInstanceOf[WorkbenchUserId]
    val owner = mockDirectoryDAO.loadUser(uid, samRequestContext).unsafeRunSync().get
    owner.googleSubjectId shouldBe Some(GoogleSubjectId(googleServicesConfig.serviceAccountClientId))
    owner.email shouldBe googleServicesConfig.serviceAccountClientEmail
    val res = mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync().get
    res.id shouldBe resourceAndPolicyName
    res.members shouldBe Set(owner.id)
    res.roles shouldBe Set(ResourceRoleName("owner"))
    // make sure a repeated call does not fail
    ge.onBoot(app).unsafeRunSync()

  }

  it should "include username, subject ID, and apps domain in proxy group email" in {
    val appsDomain = "test.cloudfire.org"

    val config = googleServicesConfig.copy(appsDomain = appsDomain)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      config,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val userId = UserService.genWorkbenchUserId(System.currentTimeMillis())
    val proxyEmail = googleExtensions.toProxyFromUser(userId).value
    proxyEmail shouldBe s"PROXY_$userId@test.cloudfire.org"
  }

  it should "truncate username if proxy group email would otherwise be too long" in {
    val config = googleServicesConfig.copy(appsDomain = "test.cloudfire.org")
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      config,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val userId = UserService.genWorkbenchUserId(System.currentTimeMillis())

    val proxyEmail = googleExtensions.toProxyFromUser(userId).value
    proxyEmail shouldBe s"PROXY_$userId@test.cloudfire.org"
  }

  it should "do Googley stuff onUserCreate" in {
    val user = Generator.genWorkbenchUserGoogle.sample.get
    val proxyEmail = WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")

    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val allUsersGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set.empty, WorkbenchEmail(s"TEST_ALL_USERS_GROUP@test.firecloud.org"))
    val allUsersGroupNameMatcher = new ArgumentMatcher[WorkbenchGroupName] {
      override def matches(name: WorkbenchGroupName): Boolean = name == allUsersGroup.id
    }
    when(mockDirectoryDAO.loadGroup(argThat(allUsersGroupNameMatcher), any[SamRequestContext])).thenReturn(IO.pure(Some(allUsersGroup)))

    when(mockGoogleDirectoryDAO.getGoogleGroup(any[WorkbenchEmail])).thenReturn(Future.successful(None))
    when(mockGoogleDirectoryDAO.createGroup(any[String], any[WorkbenchEmail], any[Option[Groups]])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.lockedDownGroupSettings).thenCallRealMethod()

    googleExtensions.onUserCreate(user, samRequestContext).unsafeRunSync()

    val lockedDownGroupSettings = Option(mockGoogleDirectoryDAO.lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).createGroup(user.email.value, proxyEmail, lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(allUsersGroup.email, proxyEmail)
  }

  it should "do Googley stuff onGroupDelete" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val testPolicy = BasicWorkbenchGroup(WorkbenchGroupName("blahblahblah"), Set.empty, WorkbenchEmail(s"blahblahblah@test.firecloud.org"))

    when(mockGoogleDirectoryDAO.deleteGroup(testPolicy.email)).thenReturn(Future.successful(()))

    googleExtensions.onGroupDelete(testPolicy.email).unsafeRunSync()

    verify(mockGoogleDirectoryDAO).deleteGroup(testPolicy.email)
  }

  "onGroupUpdate" should "trigger updates to synced constrained policies if updating a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      null,
      null,
      mockGoogleGroupSyncPubSubDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val managedGroupId = "managedGroupId"

    val managedGroupRPN =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)
    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[MessageRequest]])).thenReturn(Future.successful(()))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext))
      .thenReturn(IO.pure(Set(ownerRPN, readerRPN)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(managedGroupRPN), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[MessageRequest]])
  }

  it should "trigger updates to constrained policies when updating a group that is a part of a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      null,
      null,
      mockGoogleGroupSyncPubSubDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[MessageRequest]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish subgroup relationship to managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId), samRequestContext))
      .thenReturn(IO.pure(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext))
      .thenReturn(IO.pure(Set(ownerRPN, readerRPN)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId)), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[MessageRequest]])
  }

  it should "break out of the loop" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      mockDirectoryDAO,
      mockAccessPolicyDAO,
      null,
      null,
      mockGoogleGroupSyncPubSubDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      null,
      configResourceTypes,
      superAdminsGroup
    )

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext]))
      .thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[MessageRequest]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish nested group structure for owner policy and subgroup in managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId), samRequestContext))
      .thenReturn(IO.pure(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext))
      .thenReturn(IO.pure(Set(ownerRPN, readerRPN)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId)), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[MessageRequest]])
  }

  private def setupGoogleKeyCacheTestsWithRealKey: (GoogleExtensions, UserService, TosService) =
    setupGoogleKeyCacheTests(true)

  private def setupGoogleKeyCacheTests: (GoogleExtensions, UserService, TosService) =
    setupGoogleKeyCacheTests(false)
  private def setupGoogleKeyCacheTests(realKey: Boolean): (GoogleExtensions, UserService, TosService) = {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = if (realKey) new RealKeyMockGoogleIamDAO else new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleKeyCachePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(
      TestSupport.distributedLock,
      mockGoogleIamDAO,
      mockGoogleStorageDAO,
      FakeGoogleStorageInterpreter,
      mockGoogleKeyCachePubSubDAO,
      googleServicesConfig,
      petServiceAccountConfig
    )

    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      mockGoogleDirectoryDAO,
      mockGoogleNotificationPubSubDAO,
      null,
      null,
      mockGoogleIamDAO,
      mockGoogleStorageDAO,
      mockGoogleProjectDAO,
      googleKeyCache,
      notificationDAO,
      null,
      FakeGoogleStorageInterpreter,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )
    val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)
    val service = new UserService(dirDAO, googleExtensions, Seq.empty, tosService)

    (googleExtensions, service, tosService)
  }

  "GoogleKeyCache" should "create a service account key and return the same key when called again" in {
    assume(databaseEnabled, databaseEnabledClue)

    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service, tosService) = setupGoogleKeyCacheTests

    val createDefaultUser = Generator.genWorkbenchUserGoogle.sample.get
    // create a user
    val newUser = newUserWithAcceptedTos(service, tosService, createDefaultUser, samRequestContext)
    newUser shouldBe UserStatus(
      UserStatusDetails(createDefaultUser.id, createDefaultUser.email),
      TestSupport.enabledMapTosAccepted
    )

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // get a key, which should create a brand new one
    val firstKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()
    // get a key again, which should return the original cached key created above
    val secondKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()
    assert(firstKey == secondKey)
  }

  it should "remove an existing key and then return a brand new one" in {
    assume(databaseEnabled, databaseEnabledClue)

    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service, tosService) = setupGoogleKeyCacheTests

    val createDefaultUser = Generator.genWorkbenchUserGoogle.sample.get
    // create a user
    val newUser = newUserWithAcceptedTos(service, tosService, createDefaultUser, samRequestContext)
    newUser shouldBe UserStatus(
      UserStatusDetails(createDefaultUser.id, createDefaultUser.email),
      TestSupport.enabledMapTosAccepted
    )

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // get a key, which should create a brand new one
    val firstKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // remove the key we just created
    runAndWait(for {
      keys <- googleExtensions.googleIamDAO.listServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email)
      _ <- keys.toList
        .parTraverse { key =>
          googleExtensions.petServiceAccounts.removePetServiceAccountKey(createDefaultUser.id, googleProject, key.id, samRequestContext)
        }
        .unsafeToFuture()
    } yield ())

    // get a key again, which should once again create a brand new one because we've deleted the cached one
    val secondKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    assert(firstKey != secondKey)
  }

  it should "clean up unknown pet SA keys" in {
    assume(databaseEnabled, databaseEnabledClue)

    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service, tosService) = setupGoogleKeyCacheTests

    val createDefaultUser = Generator.genWorkbenchUserGoogle.sample.get
    // create a user
    val newUser = newUserWithAcceptedTos(service, tosService, createDefaultUser, samRequestContext)
    newUser shouldBe UserStatus(
      UserStatusDetails(createDefaultUser.id, createDefaultUser.email),
      TestSupport.enabledMapTosAccepted
    )

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.petServiceAccounts.createUserPetServiceAccount(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // get a key, which should create a brand new one
    val firstKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // remove the key we just created behind the scenes
    val removedKeyObjects = (for {
      keyObject <- googleExtensions.googleKeyCache.googleStorageAlg.listObjectsWithPrefix(
        googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName,
        googleExtensions.googleKeyCache.keyNamePrefix(googleProject, petServiceAccount.serviceAccount.email)
      )
      _ <- googleExtensions.googleKeyCache.googleStorageAlg.removeObject(
        googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName,
        GcsBlobName(keyObject.value)
      )
    } yield keyObject).compile.toList.unsafeRunSync()

    // assert that keys still exist on service account
    assert(removedKeyObjects.forall { removed =>
      val existingKeys = runAndWait(googleExtensions.googleIamDAO.listUserManagedServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email))
      existingKeys.exists(key => removed.value.endsWith(key.id.value))
    })

    // get a key again, which should once again create a brand new one because we've deleted the cached one
    // and all the keys removed should have been removed from google
    val secondKey = googleExtensions.petServiceAccounts.getPetServiceAccountKey(createDefaultUser, googleProject, samRequestContext).unsafeRunSync()

    // assert that keys have been removed from service account
    assert(removedKeyObjects.forall { removed =>
      val existingKeys = runAndWait(googleExtensions.googleIamDAO.listUserManagedServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email))
      !existingKeys.exists(key => removed.value.endsWith(key.id.value))
    })

    assert(firstKey != secondKey)
  }

  /** Function to initialize the necessary state for the tests related to private functions isConstrainable and calculateIntersectionGroup In addition to the
    * values it returns, this function creates the 'constrainableResourceType' and the 'managedGroupResourceType' in the ResourceService and clears the database
    */
  private def initPrivateTest: (DirectoryDAO, GoogleExtensions, ResourceService, ManagedGroupService, ResourceType, ResourceRole, GoogleGroupSynchronizer) = {
    // Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
    val realResourceTypes = TestSupport.appConfig.resourceTypes
    val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap
    val managedGroupResourceType =
      realResourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

    val dirDAO = newDirectoryDAO()
    val policyDAO = newAccessPolicyDAO()

    clearDatabase()

    val constrainableActionPatterns = Set(ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true))

    val constrainableViewAction = ResourceAction("constrainable_view")
    val constrainableResourceTypeActions = Set(constrainableViewAction)
    val constrainableReaderRoleName = ResourceRoleName("constrainable_reader")
    val constrainableRole = ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)
    val constrainableResourceType = ResourceType(
      ResourceTypeName(UUID.randomUUID().toString),
      constrainableActionPatterns,
      Set(ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)),
      constrainableReaderRoleName
    )
    val constrainableResourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)

    val emailDomain = "example.com"

    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      policyDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      constrainableResourceTypes,
      superAdminsGroup
    )
    val constrainablePolicyEvaluatorService = PolicyEvaluatorService(emailDomain, constrainableResourceTypes, policyDAO, dirDAO)
    val constrainableService =
      new ResourceService(constrainableResourceTypes, constrainablePolicyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain, Set.empty)
    val managedGroupService = new ManagedGroupService(
      constrainableService,
      constrainablePolicyEvaluatorService,
      constrainableResourceTypes,
      policyDAO,
      dirDAO,
      NoExtensions,
      emailDomain
    )

    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()
    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()

    val googleGroupSynchronizer = new GoogleGroupSynchronizer(dirDAO, policyDAO, null, googleExtensions, constrainableResourceTypes)
    (dirDAO, googleExtensions, constrainableService, managedGroupService, constrainableResourceType, constrainableRole, googleGroupSynchronizer)
  }

  "calculateIntersectionGroup" should "find the intersection of the resource auth domain and the policy" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      managedGroupService: ManagedGroupService,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val inAuthDomainUser = Generator.genWorkbenchUserBoth.sample.get
    val inPolicyUser = Generator.genWorkbenchUserBoth.sample.get
    val inBothUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inAuthDomainUser, samRequestContext).unsafeRunSync()
    dirDAO.createUser(inPolicyUser, samRequestContext).unsafeRunSync()
    dirDAO.createUser(inBothUser, samRequestContext).unsafeRunSync()

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser, samRequestContext = samRequestContext))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothUser.id, samRequestContext))

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(inPolicyUser.email, inBothUser.email),
        constrainableRole.actions,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(
        constrainableResourceType,
        ResourceId("rid"),
        accessPolicyMap,
        Set(WorkbenchGroupName(managedGroupId)),
        None,
        inBothUser.id,
        samRequestContext
      )
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set(inPolicyUser.email, inBothUser.email), Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothUser.id)
  }

  it should "handle nested group structures for policies and auth domains" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      managedGroupService: ManagedGroupService,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    // User in owner policy of both auth domain and resource to be used during creation of the managed group and resource
    val superAdminOwner = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(superAdminOwner, samRequestContext).unsafeRunSync()

    // User in subgroup within auth domain; will not be in intersection group
    val inAuthDomainSubGroupUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inAuthDomainSubGroupUser, samRequestContext).unsafeRunSync()

    // User in subgroup within policy; will not be in intersection group
    val inPolicySubGroupUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inPolicySubGroupUser, samRequestContext).unsafeRunSync()

    // User in subgroup within both policy and auth domain; will be in intersection group
    val inBothSubGroupUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inBothSubGroupUser, samRequestContext).unsafeRunSync()

    // Create subgroups as groups in the database
    val inAuthDomainSubGroup = dirDAO
      .createGroup(
        BasicWorkbenchGroup(WorkbenchGroupName("inAuthDomainSubGroup"), Set(inAuthDomainSubGroupUser.id), WorkbenchEmail("imAuthDomain@subGroup.com")),
        samRequestContext = samRequestContext
      )
      .unsafeRunSync()
    val inPolicySubGroup = dirDAO
      .createGroup(
        BasicWorkbenchGroup(WorkbenchGroupName("inPolicySubGroup"), Set(inPolicySubGroupUser.id), WorkbenchEmail("inPolicy@subGroup.com")),
        samRequestContext = samRequestContext
      )
      .unsafeRunSync()
    val inBothSubGroup = dirDAO
      .createGroup(
        BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set(inBothSubGroupUser.id), WorkbenchEmail("inBoth@subGroup.com")),
        samRequestContext = samRequestContext
      )
      .unsafeRunSync()

    // Create managed group to act as auth domain and add appropriate subgroups
    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), superAdminOwner, samRequestContext = samRequestContext))
    runAndWait(
      managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inAuthDomainSubGroup.id, samRequestContext)
    )
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothSubGroup.id, samRequestContext))

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(superAdminOwner.email),
        Set.empty,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(
        constrainableResourceType,
        ResourceId("rid"),
        accessPolicyMap,
        Set(WorkbenchGroupName(managedGroupId)),
        None,
        superAdminOwner.id,
        samRequestContext
      )
    )

    // Access policy that intersection group will be calculated for
    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set(inPolicySubGroup.email, inBothSubGroup.email), Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothSubGroupUser.id)
  }

  it should "return the policy members if there is no auth domain set" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      _,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val inPolicyUser = Generator.genWorkbenchUserBoth.sample.get
    val inBothUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inPolicyUser, samRequestContext).unsafeRunSync()
    dirDAO.createUser(inBothUser, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(inPolicyUser.email, inBothUser.email),
        constrainableRole.actions,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, inBothUser.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set(inPolicyUser.email, inBothUser.email), Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothUser.id, inPolicyUser.id)
  }

  it should "return an empty set if none of the policy members are in the auth domain" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      managedGroupService: ManagedGroupService,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val inAuthDomainUser = Generator.genWorkbenchUserBoth.sample.get
    val inPolicyUser = Generator.genWorkbenchUserBoth.sample.get

    dirDAO.createUser(inAuthDomainUser, samRequestContext).unsafeRunSync()
    dirDAO.createUser(inPolicyUser, samRequestContext).unsafeRunSync()

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser, samRequestContext = samRequestContext))

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(inPolicyUser.email),
        constrainableRole.actions,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(
        constrainableResourceType,
        ResourceId("rid"),
        accessPolicyMap,
        Set(WorkbenchGroupName(managedGroupId)),
        None,
        inAuthDomainUser.id,
        samRequestContext
      )
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set(inPolicyUser.email), Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set.empty
  }

  it should "return an empty set if both the auth domain and the policy are empty" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      managedGroupService: ManagedGroupService,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val inPolicyUser = Generator.genWorkbenchUserBoth.sample.get
    val inBothUser = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(inPolicyUser, samRequestContext).unsafeRunSync()
    dirDAO.createUser(inBothUser, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(inPolicyUser.email),
        constrainableRole.actions,
        Set(constrainableRole.roleName),
        None
      ),
      AccessPolicyName("emptyPolicy") -> AccessPolicyMembershipRequest(Set.empty, Set.empty, Set.empty, None)
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, inBothUser.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set.empty
  }

  "isConstrainable" should "return true when the policy has constrainable actions and roles" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      _,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val dummyUserInfo = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(dummyUserInfo, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(dummyUserInfo.email),
        Set.empty,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, constrainableRole.actions, Set(constrainableRole.roleName), None),
        samRequestContext
      )
    )

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable role, but no constrainable actions" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      _,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val dummyUserInfo = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(dummyUserInfo, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(dummyUserInfo.email),
        Set.empty,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, Set.empty, Set(constrainableRole.roleName), None),
        samRequestContext
      )
    )

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable action, but no constrainable roles" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      _,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val dummyUserInfo = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(dummyUserInfo, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(dummyUserInfo.email),
        Set.empty,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, constrainableRole.actions, Set.empty, None),
        samRequestContext
      )
    )

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return false when the policy is not constrainable" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (
      dirDAO: DirectoryDAO,
      ge: GoogleExtensions,
      constrainableService: ResourceService,
      _,
      constrainableResourceType: ResourceType,
      constrainableRole: ResourceRole,
      synchronizer
    ) = initPrivateTest

    val dummyUserInfo = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(dummyUserInfo, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(dummyUserInfo.email),
        Set.empty,
        Set(constrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.id, samRequestContext)
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        constrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }

  it should "return false when the resource type is not constrainable" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (dirDAO: DirectoryDAO, _, constrainableService: ResourceService, _, _, _, _) = initPrivateTest

    val dummyUserInfo = Generator.genWorkbenchUserBoth.sample.get
    dirDAO.createUser(dummyUserInfo, samRequestContext).unsafeRunSync()

    val nonConstrainableActionPatterns = Set(ResourceActionPattern("nonConstrainable_view", "Cannot be constrained by an auth domain", false))

    val nonConstrainableViewAction = ResourceAction("nonConstrainable_view")
    val nonConstrainableResourceTypeActions = Set(nonConstrainableViewAction)
    val nonConstrainableReaderRoleName = ResourceRoleName("nonConstrainable_reader")
    val nonConstrainableRole = ResourceRole(nonConstrainableReaderRoleName, nonConstrainableResourceTypeActions)
    val nonConstrainableResourceType = ResourceType(
      ResourceTypeName(UUID.randomUUID().toString),
      nonConstrainableActionPatterns,
      Set(ResourceRole(nonConstrainableReaderRoleName, nonConstrainableResourceTypeActions)),
      nonConstrainableReaderRoleName
    )

    val nonConstrainableResourceTypes = Map(nonConstrainableResourceType.name -> nonConstrainableResourceType)

    val synchronizer = new GoogleGroupSynchronizer(dirDAO, null, null, null, nonConstrainableResourceTypes)

    constrainableService.createResourceType(nonConstrainableResourceType, samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(
      AccessPolicyName(nonConstrainableRole.roleName.value) -> AccessPolicyMembershipRequest(
        Set(dummyUserInfo.email),
        nonConstrainableRole.actions,
        Set(nonConstrainableRole.roleName),
        None
      )
    )
    val resource = runAndWait(
      constrainableService.createResource(
        nonConstrainableResourceType,
        ResourceId("rid"),
        accessPolicyMap,
        Set.empty,
        None,
        dummyUserInfo.id,
        samRequestContext
      )
    )

    val accessPolicy = runAndWait(
      constrainableService.overwritePolicy(
        nonConstrainableResourceType,
        AccessPolicyName("ap"),
        resource.fullyQualifiedId,
        AccessPolicyMembershipRequest(Set.empty, Set.empty, Set.empty, None),
        samRequestContext
      )
    )

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }

  "createUserPetServiceAccount" should "return a failed IO when the project is not in the Terra Google Org" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO
    val garbageOrgGoogleServicesConfig = TestSupport.googleServicesConfig.copy(terraGoogleOrgNumber = "garbage")
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      mockGoogleIamDAO,
      null,
      mockGoogleProjectDAO,
      null,
      null,
      null,
      null,
      garbageOrgGoogleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )

    val defaultUser = Generator.genWorkbenchUserBoth.sample.get

    val googleProject = GoogleProject("testproject")
    val report = intercept[WorkbenchExceptionWithErrorReport] {
      googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    }

    report.errorReport.statusCode shouldEqual Some(StatusCodes.BadRequest)
  }

  it should "return a failed IO when google returns a 403" in {
    assume(databaseEnabled, databaseEnabledClue)

    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO {
      override def getAncestry(projectName: String): Future[Seq[Ancestor]] =
        Future.failed(new HttpResponseException.Builder(403, "Made up error message", new HttpHeaders()).build())
    }
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      dirDAO,
      null,
      mockGoogleDirectoryDAO,
      null,
      null,
      null,
      mockGoogleIamDAO,
      null,
      mockGoogleProjectDAO,
      null,
      null,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )

    val defaultUser = Generator.genWorkbenchUserBoth.sample.get

    val googleProject = GoogleProject("testproject")
    val report = intercept[WorkbenchExceptionWithErrorReport] {
      googleExtensions.petServiceAccounts.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    }

    report.errorReport.statusCode shouldEqual Some(StatusCodes.BadRequest)
  }

  "fireAndForgetNotifications" should "not fail" in {
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val topicName = "neat_topic"
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, topicName)
    val googleExtensions = new GoogleExtensions(
      TestSupport.distributedLock,
      null,
      null,
      null,
      mockGoogleNotificationPubSubDAO,
      null,
      null,
      null,
      null,
      null,
      null,
      notificationDAO,
      null,
      null,
      googleServicesConfig,
      petServiceAccountConfig,
      configResourceTypes,
      superAdminsGroup
    )

    val messages = Set(
      Notifications.GroupAccessRequestNotification(
        WorkbenchUserId("Bob"),
        WorkbenchGroupName("bobs_buds").value,
        Set(WorkbenchUserId("reply_to_address")),
        WorkbenchUserId("requesters_id")
      )
    )

    googleExtensions.fireAndForgetNotifications(messages)

    val messageLog: ConcurrentLinkedQueue[String] = mockGoogleNotificationPubSubDAO.messageLog
    val formattedMessages: Set[String] = messages.map(m => topicName + "|" + NotificationFormat.write(m).toString())
    messageLog should contain theSameElementsAs formattedMessages
  }

  protected def clearDatabase(): Unit =
    TestSupport.truncateAll
}
