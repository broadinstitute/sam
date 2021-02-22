package org.broadinstitute.dsde.workbench.sam.google

import java.net.URI
import java.util.{Date, GregorianCalendar, UUID}
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.testkit.TestKit
import cats.effect.IO
import cats.implicits._
import cats.data.NonEmptyList
import com.google.api.client.http.{HttpHeaders, HttpResponseException}
import com.google.api.services.cloudresourcemanager.model.Ancestor
import com.google.api.services.groupssettings.model.Groups
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google2.GcsBlobName
import org.broadinstitute.dsde.workbench.google.mock._
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.model.Notifications.NotificationFormat
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{WorkbenchExceptionWithErrorReport, _}
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, LdapRegistrationDAO, LoadResourceAuthDomainResult, MockAccessPolicyDAO, MockDirectoryDAO, PostgresAccessPolicyDAO, PostgresDirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model, _}
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, PrivateMethodTester}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue

class GoogleExtensionSpec(_system: ActorSystem) extends TestKit(_system) with AnyFlatSpecLike with Matchers with TestSupport with MockitoSugar with ScalaFutures with BeforeAndAfterAll with PrivateMethodTester {
  def this() = this(ActorSystem("GoogleGroupSyncMonitorSpec"))

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  lazy val directoryConfig = TestSupport.directoryConfig
  lazy val dirURI = new URI(directoryConfig.directoryUrl)
  lazy val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val schemaLockConfig = TestSupport.schemaLockConfig
  lazy val petServiceAccountConfig = TestSupport.petServiceAccountConfig
  lazy val googleServicesConfig = TestSupport.googleServicesConfig

  val configResourceTypes = TestSupport.configResourceTypes
  override implicit val patienceConfig = PatienceConfig(1 second)
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
      model.FullyQualifiedPolicyId(
        FullyQualifiedResourceId(ResourceTypeName("workspace"), ResourceId("rid")), AccessPolicyName("ap")), Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail, Set.empty, Set.empty, Set.empty, public = true)

    Seq(testGroup, testPolicy).foreach { target =>
      val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
      val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
      val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
      val mockGoogleNotificationsPubSubDAO = new MockGooglePubSubDAO
      val mockGoogleGroupSyncPubSubDAO = new MockGooglePubSubDAO
      val mockGoogleDisableUsersPubSubDAO = new MockGooglePubSubDAO
      val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGoogleNotificationsPubSubDAO, mockGoogleGroupSyncPubSubDAO, mockGoogleDisableUsersPubSubDAO, null, null,null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
      val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, ge, configResourceTypes)

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id, samRequestContext)).thenReturn(IO.pure(Option(testGroup)))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id, samRequestContext)).thenReturn(IO.pure(Option(testPolicy)))
      }
      when(mockDirectoryDAO.loadGroup(ge.allUsersGroupName, samRequestContext)).thenReturn(IO.pure(Option(BasicWorkbenchGroup(ge.allUsersGroupName, Set.empty, ge.allUsersGroupEmail))))
      when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.unit)
      when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.pure(Some((new GregorianCalendar(2017, 11, 22).getTime()))))

      val subGroups = Seq(inSamSubGroup, inGoogleSubGroup, inBothSubGroup)
      subGroups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id, samRequestContext)).thenReturn(IO.pure(Option(g.email))) }
      when(mockDirectoryDAO.loadSubjectEmail(ge.allUsersGroupName, samRequestContext)).thenReturn(IO.pure(Option(ge.allUsersGroupEmail)))

      val added = Seq(inSamSubGroup.email, WorkbenchEmail(inSamUserProxyEmail)) ++ (target match {
        case _: BasicWorkbenchGroup => Seq.empty
        case _: AccessPolicy => Set(ge.allUsersGroupEmail)
      })

      val removed = Seq(inGoogleSubGroup.email, WorkbenchEmail(inGoogleUserProxyEmail))

      when(mockGoogleDirectoryDAO.listGroupMembers(target.email)).thenReturn(Future.successful(Option(Seq(WorkbenchEmail(inGoogleUserProxyEmail).value, WorkbenchEmail(inBothUserProxyEmail).value.toLowerCase, inGoogleSubGroup.email.value, inBothSubGroup.email.value, removeError))))
      when(mockGoogleDirectoryDAO.listGroupMembers(ge.allUsersGroupEmail)).thenReturn(Future.successful(Option(Seq.empty)))
      when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

      val addException = new Exception("addError")
      when(mockGoogleDirectoryDAO.addMemberToGroup(target.email, WorkbenchEmail(addErrorProxyEmail.toLowerCase))).thenReturn(Future.failed(addException))

      val removeException = new Exception("removeError")
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(target.email, WorkbenchEmail(removeError.toLowerCase))).thenReturn(Future.failed(removeException))

      val results = runAndWait(synchronizer.synchronizeGroupMembers(target.id, samRequestContext = samRequestContext))

      results.head._1 should equal(target.email)
      results.head._2 should contain theSameElementsAs (
        added.map(e => SyncReportItem("added", e.value.toLowerCase, None)) ++
          removed.map(e => SyncReportItem("removed", e.value.toLowerCase, None)) ++
          Seq(
            SyncReportItem("added", addErrorProxyEmail.toLowerCase, Option(ErrorReport(addException))),
            SyncReportItem("removed", removeError.toLowerCase, Option(ErrorReport(removeException)))))

      added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(target.email, WorkbenchEmail(email.value.toLowerCase)) }
      removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(target.email, WorkbenchEmail(email.value.toLowerCase)) }
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

    val policyOnlySamSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inSamSubGroup"), Set(subPolicyOnlySamGroupUserId), WorkbenchEmail("inSamSubGroup@example.com"))
    val intersectionSamSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inGoogleSubGroup"), Set(subIntersectionSamGroupUserId), WorkbenchEmail("inGoogleSubGroup@example.com"))
    val authorizedGoogleSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set(subAuthorizedGoogleGroupUserId), WorkbenchEmail("inBothSubGroup@example.com"))

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
    val testPolicy = AccessPolicy(rpn, Set(policyOnlySamUserId, intersectionSamUserId, authorizedGoogleUserId, policyOnlySamSubGroup.id, intersectionSamSubGroup.id, authorizedGoogleSubGroup.id, addError), WorkbenchEmail("testPolicy@example.com"), Set(constrainableRole.roleName), constrainableRole.actions, Set.empty, public = false)

    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, mockGoogleDirectoryDAO, null, null, null, null, null,null, null, null, null, googleServicesConfig, petServiceAccountConfig, constrainableResourceTypes)
    val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO,ge, constrainableResourceTypes)

    when(mockAccessPolicyDAO.loadPolicy(testPolicy.id, samRequestContext)).thenReturn(IO.pure(Option(testPolicy)))
    when(mockAccessPolicyDAO.loadResourceAuthDomain(resource.fullyQualifiedId, samRequestContext)).thenReturn(IO.pure(LoadResourceAuthDomainResult.Constrained(NonEmptyList.one(managedGroupId)): LoadResourceAuthDomainResult))

    when(mockDirectoryDAO.listIntersectionGroupUsers(Set(managedGroupId, testPolicy.id), samRequestContext)).thenReturn(IO.pure(Set(intersectionSamUserId, authorizedGoogleUserId, subIntersectionSamGroupUserId, subAuthorizedGoogleGroupUserId, addError)))

    when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.unit)
    when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity], any[SamRequestContext])).thenReturn(IO.pure(Some(new GregorianCalendar(2017, 11, 22).getTime())))

    val added = Seq(WorkbenchEmail(intersectionSamUserProxyEmail), WorkbenchEmail(subIntersectionSamGroupUserProxyEmail))
    val removed = Seq(WorkbenchEmail(unauthorizedGoogleUserProxyEmail))

    // mock pre-sync google group members
    when(mockGoogleDirectoryDAO.listGroupMembers(testPolicy.email)).thenReturn(Future.successful(Option(Seq(authorizedGoogleUserProxyEmail, unauthorizedGoogleUserProxyEmail, subAuthorizedGoogleGroupUserProxyEmail, removeError))))
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    val addException = new Exception("addError")
    when(mockGoogleDirectoryDAO.addMemberToGroup(testPolicy.email, WorkbenchEmail(addErrorProxyEmail.toLowerCase))).thenReturn(Future.failed(addException))

    val removeException = new Exception("removeError")
    when(mockGoogleDirectoryDAO.removeMemberFromGroup(testPolicy.email, WorkbenchEmail(removeError.toLowerCase))).thenReturn(Future.failed(removeException))

    val results = runAndWait(synchronizer.synchronizeGroupMembers(testPolicy.id, samRequestContext = samRequestContext))

    results.head._1 should equal(testPolicy.email)
    results.head._2 should contain theSameElementsAs (
      added.map(e => SyncReportItem("added", e.value.toLowerCase, None)) ++
        removed.map(e => SyncReportItem("removed", e.value.toLowerCase, None)) ++
        Seq(
          SyncReportItem("added", addErrorProxyEmail.toLowerCase, Option(ErrorReport(addException))),
          SyncReportItem("removed", removeError.toLowerCase, Option(ErrorReport(removeException)))))

    added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)) }
    removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)) }
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
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGoogleNotificationsPubSubDAO, mockGoogleGroupSyncPubSubDAO, mockGoogleDisableUsersPubSubDAO, mockGoogleIamDAO, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    val synchronizer = new GoogleGroupSynchronizer(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO,ge, configResourceTypes)

    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    //create groups
    mockDirectoryDAO.createGroup(topGroup, samRequestContext = samRequestContext).unsafeRunSync()
    mockDirectoryDAO.createGroup(subGroup, samRequestContext = samRequestContext).unsafeRunSync()
    //add subGroup to topGroup
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(groupEmail, subGroupEmail))
    mockDirectoryDAO.addGroupMember(topGroup.id, subGroup.id, samRequestContext).unsafeRunSync()
    //add topGroup to subGroup - creating cycle
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(subGroupEmail, groupEmail))
    mockDirectoryDAO.addGroupMember(subGroup.id, topGroup.id, samRequestContext).unsafeRunSync()
    when(mockGoogleDirectoryDAO.listGroupMembers(topGroup.email)).thenReturn(Future.successful(Option(Seq(subGroupEmail.value))))
    when(mockGoogleDirectoryDAO.listGroupMembers(subGroup.email)).thenReturn(Future.successful(Option(Seq(groupEmail.value))))
    val syncedEmails = runAndWait(synchronizer.synchronizeGroupMembers(topGroup.id, samRequestContext = samRequestContext)).keys
    syncedEmails shouldEqual Set(groupEmail, subGroupEmail)
  }

  "GoogleExtension" should "get a pet service account for a user" in {
    val (dirDAO: DirectoryDAO, _: RegistrationDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    val defaultUser = WorkbenchUser(createDefaultUser.id,  Some(createDefaultUser.googleSubjectId), createDefaultUser.email, createDefaultUser.identityConcentratorId)
    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    petServiceAccount.serviceAccount.email.value should endWith(s"@${googleProject.value}.iam.gserviceaccount.com")

    // verify ldap
    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUserId, googleProject), samRequestContext).unsafeRunSync() shouldBe Some(petServiceAccount)

    val ldapPetOpt = dirDAO.loadSubjectFromEmail(petServiceAccount.serviceAccount.email, samRequestContext).flatMap {
      case Some(subject: PetServiceAccountId) => dirDAO.loadPetServiceAccount(subject, samRequestContext)
      case _ => fail(s"could not load pet LDAP entry from ${petServiceAccount.serviceAccount.email.value}")
    }.unsafeRunSync()

    ldapPetOpt shouldBe Symbol("defined")
    val Some(ldapPet) = ldapPetOpt
    // MockGoogleIamDAO generates the subject ID as a random Long
    Try(ldapPet.serviceAccount.subjectId.value.toLong) shouldBe a[Success[_]]

    // verify google
    mockGoogleIamDAO.serviceAccounts should contain key petServiceAccount.serviceAccount.email
    mockGoogleDirectoryDAO.groups should contain key defaultUserProxyEmail
    mockGoogleDirectoryDAO.groups(defaultUserProxyEmail) shouldBe Set(defaultUserEmail, petServiceAccount.serviceAccount.email)

    // create one again, it should work
    val petSaResponse2 = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petSaResponse2 shouldBe petServiceAccount

    // delete the pet service account
    googleExtensions.deleteUserPetServiceAccount(newUser.userStatusDetails.userSubjectId, googleProject, samRequestContext).unsafeRunSync() shouldBe true

    // the user should still exist in LDAP
    dirDAO.loadUser(defaultUserId, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser)

    // the pet should not exist in LDAP
    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUserId, googleProject), samRequestContext).unsafeRunSync() shouldBe None

    // the pet should not exist in Google
    mockGoogleIamDAO.serviceAccounts should not contain key (petServiceAccount.serviceAccount.email)

  }

  private def initPetTest: (DirectoryDAO, RegistrationDAO, MockGoogleIamDAO, MockGoogleDirectoryDAO, GoogleExtensions, UserService, WorkbenchUserId, WorkbenchEmail, WorkbenchEmail, CreateWorkbenchUser) = {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO

    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, newRegistrationDAO(), null, mockGoogleDirectoryDAO, null, null, null, mockGoogleIamDAO, null, mockGoogleProjectDAO, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    val service = new UserService(dirDAO, googleExtensions, googleExtensions.registrationDAO, Seq.empty)

    val defaultUserId = WorkbenchUserId("newuser123")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_newuser123@${googleServicesConfig.appsDomain}")

    val defaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail, None)
    (dirDAO, regDAO, mockGoogleIamDAO, mockGoogleDirectoryDAO, googleExtensions, service, defaultUserId, defaultUserEmail, defaultUserProxyEmail, defaultUser)
  }

  protected def newDirectoryDAO(): DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  protected def newRegistrationDAO(): RegistrationDAO = new LdapRegistrationDAO(connectionPool, directoryConfig, TestSupport.blockingEc)
  protected def newAccessPolicyDAO(): AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)

  it should "attach existing service account to pet" in {
    val (dirDAO: DirectoryDAO, _: RegistrationDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest
    val googleProject = GoogleProject("testproject")

    val defaultUser = WorkbenchUser(createDefaultUser.id, None, createDefaultUser.email, None)
    val (saName, saDisplayName) = googleExtensions.toPetSAFromUser(defaultUser)
    val serviceAccount = mockGoogleIamDAO.createServiceAccount(googleProject, saName, saDisplayName).futureValue
    // create a user

    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petServiceAccount.serviceAccount shouldBe serviceAccount

  }

  it should "recreate service account when missing for pet" in {
    val (dirDAO: DirectoryDAO, regDAO: RegistrationDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    val defaultUser = WorkbenchUser(createDefaultUser.id, None, createDefaultUser.email, None)
    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    import org.broadinstitute.dsde.workbench.model.google.toAccountName
    mockGoogleIamDAO.removeServiceAccount(googleProject, toAccountName(petServiceAccount.serviceAccount.email)).futureValue
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe None

    val petServiceAccount2 = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    petServiceAccount.serviceAccount shouldNot equal(petServiceAccount2.serviceAccount)
    val res = dirDAO.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync()
    res shouldBe Some(petServiceAccount2)
    regDAO.loadPetServiceAccount(petServiceAccount.id, samRequestContext).unsafeRunSync() shouldBe res
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe Some(petServiceAccount2.serviceAccount)
  }

  it should "get a group's last synchronized date" in {
    val groupName = WorkbenchGroupName("group1")

    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    when(mockDirectoryDAO.getSynchronizedDate(groupName, samRequestContext)).thenReturn(IO.pure(None))
    ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe None

    val date = new Date()
    when(mockDirectoryDAO.getSynchronizedDate(groupName, samRequestContext)).thenReturn(IO.pure(Some(date)))
    ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe Some(date)
  }

  it should "throw an exception with a NotFound error report when getting sync date for group that does not exist" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync()
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return None when getting sync date for a group that has not been synced" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO,null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail("")), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync() shouldBe None
    } finally {
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
    }
  }

  it should "return sync date for a group that has been synced" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, new MockGoogleDirectoryDAO(), null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val synchronizer = new GoogleGroupSynchronizer(dirDAO, null, mockGoogleDirectoryDAO, ge, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail("group1@test.firecloud.org")), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      runAndWait(synchronizer.synchronizeGroupMembers(groupName, samRequestContext = samRequestContext))
      val syncDate = ge.getSynchronizedDate(groupName, samRequestContext).unsafeRunSync().get
      syncDate.getTime should equal (new Date().getTime +- 1.second.toMillis)
    } finally {
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
    }
  }

  it should "throw an exception with a NotFound error report when getting email for group that does not exist" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      ge.getSynchronizedEmail(groupName, samRequestContext).unsafeRunSync()
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return email for a group" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      ge.getSynchronizedEmail(groupName, samRequestContext).unsafeRunSync() shouldBe Some(email)
    } finally {
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
    }
  }

  it should "return None if an email is found, but the group has not been synced" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync() shouldBe None
    } finally {
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
    }
  }

  it should "return SyncState with email and last sync date if there is an email and the group has been synced" in {
    val dirDAO = newDirectoryDAO()
    val regDAO = newRegistrationDAO()
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO()
    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, mockGoogleDirectoryDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val synchronizer = new GoogleGroupSynchronizer(dirDAO, null, mockGoogleDirectoryDAO, ge, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email), samRequestContext = samRequestContext).unsafeRunSync()
    try {
      ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync() should equal(None)
      runAndWait(synchronizer.synchronizeGroupMembers(groupName, samRequestContext = samRequestContext))
      val maybeSyncResponse = ge.getSynchronizedState(groupName, samRequestContext).unsafeRunSync()
      maybeSyncResponse.map(_.email) should equal(Some(email))
    } finally {
      dirDAO.deleteGroup(groupName, samRequestContext).unsafeRunSync()
    }
  }

  it should "create google extension resource on boot" in {
    val mockAccessPolicyDAO = new MockAccessPolicyDAO()
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockRegistrationDAO = new MockDirectoryDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleKeyCachePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleGroupSyncPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleDisableUsersPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(
      TestSupport.fakeDistributedLock, mockGoogleIamDAO, mockGoogleStorageDAO, FakeGoogleStorageInterpreter, mockGoogleKeyCachePubSubDAO, googleServicesConfig, petServiceAccountConfig)

    val ge = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mockRegistrationDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGoogleNotificationPubSubDAO, mockGoogleGroupSyncPubSubDAO, mockGoogleDisableUsersPubSubDAO, mockGoogleIamDAO, mockGoogleStorageDAO, null, googleKeyCache, notificationDAO, FakeGoogleKmsInterpreter, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    val app = SamApplication(new UserService(mockDirectoryDAO, ge, mockRegistrationDAO, Seq.empty), new ResourceService(configResourceTypes, null, mockAccessPolicyDAO, mockDirectoryDAO, ge, "example.com"), null)
    val resourceAndPolicyName = FullyQualifiedPolicyId(FullyQualifiedResourceId(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), AccessPolicyName("owner"))

    mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId), samRequestContext).unsafeRunSync() shouldBe None
    mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).unsafeRunSync() shouldBe None

    ge.onBoot(app).unsafeRunSync()

    val uid = mockDirectoryDAO.loadSubjectFromGoogleSubjectId(GoogleSubjectId(googleServicesConfig.serviceAccountClientId), samRequestContext).unsafeRunSync().get.asInstanceOf[WorkbenchUserId]
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
    val subjectId = "0123456789"
    val username = "foo"

    val config = googleServicesConfig.copy(appsDomain = appsDomain)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, null, null, null, null, null, null, null, null, null, null, null, null, null, config, null, configResourceTypes)

    val user = WorkbenchUser(WorkbenchUserId(subjectId), None, WorkbenchEmail(s"$username@test.org"), None)

    val proxyEmail = googleExtensions.toProxyFromUser(user.id).value
    proxyEmail shouldBe "PROXY_0123456789@test.cloudfire.org"
  }

  it should "truncate username if proxy group email would otherwise be too long" in {
    val config = googleServicesConfig.copy(appsDomain = "test.cloudfire.org")
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, null, null, null, null, null, null, null, null, null, null, null, null, null, config, null, configResourceTypes)

    val user = WorkbenchUser(WorkbenchUserId("0123456789"), None, WorkbenchEmail("foo-bar-baz-qux-quux-corge-grault-garply@test.org"), None)

    val proxyEmail = googleExtensions.toProxyFromUser(user.id).value
    proxyEmail shouldBe "PROXY_0123456789@test.cloudfire.org"
  }

  it should "do Googley stuff onUserCreate" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val userEmail = WorkbenchEmail("foo@test.org")
    val user = WorkbenchUser(userId, None, userEmail, None)
    val proxyEmail = WorkbenchEmail(s"PROXY_$userId@${googleServicesConfig.appsDomain}")

    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), null, mockGoogleDirectoryDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val allUsersGroup = BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, WorkbenchEmail(s"TEST_ALL_USERS_GROUP@test.firecloud.org"))
    val allUsersGroupMatcher = new ArgumentMatcher[BasicWorkbenchGroup] {
      override def matches(group: BasicWorkbenchGroup): Boolean = group.id == allUsersGroup.id
    }
    when(mockDirectoryDAO.createGroup(argThat(allUsersGroupMatcher), any[Option[String]], any[SamRequestContext])).thenReturn(IO.pure(allUsersGroup))

    when(mockGoogleDirectoryDAO.getGoogleGroup(any[WorkbenchEmail])).thenReturn(Future.successful(None))
    when(mockGoogleDirectoryDAO.createGroup(any[String], any[WorkbenchEmail], any[Option[Groups]])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    googleExtensions.onUserCreate(user, samRequestContext).futureValue

    val lockedDownGroupSettings = Option(mockGoogleDirectoryDAO.lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).createGroup(userEmail.value, proxyEmail, lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(allUsersGroup.email, proxyEmail)
  }

  it should "do Googley stuff onGroupDelete" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), null, mockGoogleDirectoryDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val testPolicy = BasicWorkbenchGroup(WorkbenchGroupName("blahblahblah"), Set.empty, WorkbenchEmail(s"blahblahblah@test.firecloud.org"))

    when(mockDirectoryDAO.deleteGroup(testPolicy.id, samRequestContext)).thenReturn(IO.unit)

    googleExtensions.onGroupDelete(testPolicy.email)

    verify(mockGoogleDirectoryDAO).deleteGroup(testPolicy.email)
  }

  "onGroupUpdate" should "trigger updates to constrained policies if updating a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, null, null, mockGoogleGroupSyncPubSubDAO, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"

    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)
    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext)).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId, samRequestContext)).thenReturn(IO.pure(LazyList(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(managedGroupRPN), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[String]])
  }

  it should "trigger updates to constrained policies when updating a group that is a part of a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, null, null, mockGoogleGroupSyncPubSubDAO, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish subgroup relationship to managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId), samRequestContext)).thenReturn(IO.pure(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext)).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId, samRequestContext)).thenReturn(IO.pure(LazyList(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId)), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[String]])
  }

  it should "break out of the loop" in {
    val mockDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
    val mockAccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)
    val mockGoogleGroupSyncPubSubDAO = mock[MockGooglePubSubDAO](RETURNS_SMART_NULLS)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, mockDirectoryDAO, mock[RegistrationDAO](RETURNS_SMART_NULLS), mockAccessPolicyDAO, null, null, mockGoogleGroupSyncPubSubDAO, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), ManagedGroupService.memberPolicyName)

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId], any[SamRequestContext])).thenReturn(IO.pure(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGoogleGroupSyncPubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish nested group structure for owner policy and subgroup in managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId), samRequestContext)).thenReturn(IO.pure(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.listAncestorGroups(ownerRPN, samRequestContext)).thenReturn(IO.pure(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId), samRequestContext)).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId, samRequestContext)).thenReturn(IO.pure(LazyList(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId)), samRequestContext))

    verify(mockGoogleGroupSyncPubSubDAO, times(1)).publishMessages(any[String], any[Seq[String]])
  }

  private def setupGoogleKeyCacheTests: (GoogleExtensions, UserService) = {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleKeyCachePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(TestSupport.fakeDistributedLock, mockGoogleIamDAO, mockGoogleStorageDAO, FakeGoogleStorageInterpreter, mockGoogleKeyCachePubSubDAO, googleServicesConfig, petServiceAccountConfig)

    val regDAO = newRegistrationDAO()
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, regDAO, null, mockGoogleDirectoryDAO, mockGoogleNotificationPubSubDAO, null, null, mockGoogleIamDAO, mockGoogleStorageDAO, mockGoogleProjectDAO, googleKeyCache, notificationDAO, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    val service = new UserService(dirDAO, googleExtensions, regDAO, Seq.empty)

    (googleExtensions, service)
  }

  "GoogleKeyCache" should "create a service account key and return the same key when called again" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val createDefaultUser = Generator.genCreateWorkbenchUser.sample.get
    val defaultUser = WorkbenchUser(createDefaultUser.id,  Some(createDefaultUser.googleSubjectId), createDefaultUser.email, createDefaultUser.identityConcentratorId)

    // create a user
    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    //get a key, which should create a brand new one
    val firstKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    //get a key again, which should return the original cached key created above
    val secondKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    assert(firstKey == secondKey)
  }

  it should "remove an existing key and then return a brand new one" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val createDefaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail, None)
    val defaultUser = WorkbenchUser(defaultUserId, None, defaultUserEmail, None)

    // create a user
    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    //get a key, which should create a brand new one
    val firstKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    //remove the key we just created
    runAndWait(for {
      keys <- googleExtensions.googleIamDAO.listServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email)
      _ <- keys.toList.parTraverse { key =>
        googleExtensions.removePetServiceAccountKey(defaultUserId, googleProject, key.id, samRequestContext)
      }.unsafeToFuture()
    } yield ())

    //get a key again, which should once again create a brand new one because we've deleted the cached one
    val secondKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    assert(firstKey != secondKey)
  }

  it should "clean up unknown pet SA keys" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val createDefaultUser = Generator.genCreateWorkbenchUser.sample.get
    val defaultUser = WorkbenchUser(createDefaultUser.id,  Some(createDefaultUser.googleSubjectId), createDefaultUser.email, createDefaultUser.identityConcentratorId)

    // create a user
    val newUser = service.createUser(createDefaultUser, samRequestContext).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    //get a key, which should create a brand new one
    val firstKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    //remove the key we just created behind the scenes
    val removedKeyObjects = (for {
      keyObject <- googleExtensions.googleKeyCache.googleStorageAlg.listObjectsWithPrefix(googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName, googleExtensions.googleKeyCache.keyNamePrefix(googleProject, petServiceAccount.serviceAccount.email))
      _ <- googleExtensions.googleKeyCache.googleStorageAlg.removeObject(googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(keyObject.value))
    } yield keyObject).compile.toList.unsafeRunSync()

    // assert that keys still exist on service account
    assert(removedKeyObjects.forall { removed =>
      val existingKeys = runAndWait(googleExtensions.googleIamDAO.listUserManagedServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email))
      existingKeys.exists(key => removed.value.endsWith(key.id.value))
    })

    //get a key again, which should once again create a brand new one because we've deleted the cached one
    //and all the keys removed should have been removed from google
    val secondKey = googleExtensions.getPetServiceAccountKey(defaultUser, googleProject, samRequestContext).unsafeRunSync()

    // assert that keys have been removed from service account
    assert(removedKeyObjects.forall { removed =>
      val existingKeys = runAndWait(googleExtensions.googleIamDAO.listUserManagedServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email))
      !existingKeys.exists(key => removed.value.endsWith(key.id.value))
    })

    assert(firstKey != secondKey)
  }

  /**
    * Function to initialize the necessary state for the tests related to private functions isConstrainable and calculateIntersectionGroup
    * In addition to the values it returns, this function creates the 'constrainableResourceType' and the 'managedGroupResourceType' in
    * the ResourceService and clears the database
    */
  private def initPrivateTest: (DirectoryDAO, RegistrationDAO, GoogleExtensions, ResourceService, ManagedGroupService, ResourceType, ResourceRole, GoogleGroupSynchronizer) = {
    implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
    //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
    val realResourceTypes = TestSupport.appConfig.resourceTypes
    val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap
    val managedGroupResourceType = realResourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

    val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
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

    val registrationDAO = newRegistrationDAO()
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, registrationDAO, policyDAO, null, null, null, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, constrainableResourceTypes)
    val constrainablePolicyEvaluatorService = PolicyEvaluatorService(emailDomain, constrainableResourceTypes, policyDAO, dirDAO)
    val constrainableService = new ResourceService(constrainableResourceTypes, constrainablePolicyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
    val managedGroupService = new ManagedGroupService(constrainableService, constrainablePolicyEvaluatorService, constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

    constrainableService.createResourceType(constrainableResourceType, samRequestContext).unsafeRunSync()
    constrainableService.createResourceType(managedGroupResourceType, samRequestContext).unsafeRunSync()

    val googleGroupSynchronizer = new GoogleGroupSynchronizer(dirDAO, policyDAO, null, googleExtensions, constrainableResourceTypes)
    (dirDAO, registrationDAO, googleExtensions, constrainableService, managedGroupService, constrainableResourceType, constrainableRole, googleGroupSynchronizer)
  }

  "calculateIntersectionGroup" should "find the intersection of the resource auth domain and the policy" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val inAuthDomainUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inAuthDomainUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser, samRequestContext = samRequestContext))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothUser.userId, samRequestContext))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), None, inBothUser.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), Set.empty, Set.empty, None), samRequestContext))

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothUser.userId)
  }

  it should "handle nested group structures for policies and auth domains" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    // User in owner policy of both auth domain and resource to be used during creation of the managed group and resource
    val superAdminOwner = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("authDomainOwner"), WorkbenchEmail("authDomainOwner@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(superAdminOwner.userId, Some(TestSupport.genGoogleSubjectId()), superAdminOwner.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    // User in subgroup within auth domain; will not be in intersection group
    val inAuthDomainSubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inAuthDomainSubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainSubGroupUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    // User in subgroup within policy; will not be in intersection group
    val inPolicySubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inPolicySubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicySubGroupUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    // User in subgroup within both policy and auth domain; will be in intersection group
    val inBothSubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inBothSubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothSubGroupUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    // Create subgroups as groups in ldap
    val inAuthDomainSubGroup = dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inAuthDomainSubGroup"), Set(inAuthDomainSubGroupUser.userId), WorkbenchEmail("imAuthDomain@subGroup.com")), samRequestContext = samRequestContext).unsafeRunSync()
    val inPolicySubGroup = dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inPolicySubGroup"), Set(inPolicySubGroupUser.userId), WorkbenchEmail("inPolicy@subGroup.com")), samRequestContext = samRequestContext).unsafeRunSync()
    val inBothSubGroup = dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set(inBothSubGroupUser.userId), WorkbenchEmail("inBoth@subGroup.com")), samRequestContext = samRequestContext).unsafeRunSync()

    // Create managed group to act as auth domain and add appropriate subgroups
    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), superAdminOwner, samRequestContext = samRequestContext))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inAuthDomainSubGroup.id, samRequestContext))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothSubGroup.id, samRequestContext))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(superAdminOwner.userEmail), Set.empty, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), None, superAdminOwner.userId, samRequestContext))

    // Access policy that intersection group will be calculated for
    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicySubGroup.email, inBothSubGroup.email), Set.empty, Set.empty, None), samRequestContext))

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothSubGroupUser.userId)
  }

  it should "return the policy members if there is no auth domain set" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, inBothUser.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), Set.empty, Set.empty, None), samRequestContext))

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set(inBothUser.userId, inPolicyUser.userId)
  }

  it should "return an empty set if none of the policy members are in the auth domain" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val inAuthDomainUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)

    dirDAO.createUser(WorkbenchUser(inAuthDomainUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser, samRequestContext = samRequestContext))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), None, inAuthDomainUser.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail), Set.empty, Set.empty, None), samRequestContext))

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set.empty
  }

  it should "return an empty set if both the auth domain and the policy are empty" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName), None),
      AccessPolicyName("emptyPolicy") -> AccessPolicyMembership(Set.empty, Set.empty, Set.empty, None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, inBothUser.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty, None), samRequestContext))

    val intersectionGroup = synchronizer.calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy, samRequestContext).unsafeRunSync()
    intersectionGroup shouldEqual Set.empty
  }

  "isConstrainable" should "return true when the policy has constrainable actions and roles" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, constrainableRole.actions, Set(constrainableRole.roleName), None), samRequestContext))

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable role, but no constrainable actions" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set(constrainableRole.roleName), None), samRequestContext))

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable action, but no constrainable roles" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, constrainableRole.actions, Set.empty, None), samRequestContext))

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return false when the policy is not constrainable" in {
    val (dirDAO: DirectoryDAO, _, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole, synchronizer) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty, None), samRequestContext))

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }

  it should "return false when the resource type is not constrainable" in {
    val (dirDAO: DirectoryDAO, regDAO, _, constrainableService: ResourceService, _, _, _, _) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())), samRequestContext).unsafeRunSync()

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

    val accessPolicyMap = Map(AccessPolicyName(nonConstrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), nonConstrainableRole.actions, Set(nonConstrainableRole.roleName), None))
    val resource = runAndWait(constrainableService.createResource(nonConstrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, None, dummyUserInfo.userId, samRequestContext))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(nonConstrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty, None), samRequestContext))

    val constrained = synchronizer.isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }

  "createUserPetServiceAccount" should "return a failed IO when the project is not in the Terra Google Org" in {
    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO
    val garbageOrgGoogleServicesConfig = TestSupport.googleServicesConfig.copy(terraGoogleOrgNumber = "garbage")
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, newRegistrationDAO(), null, mockGoogleDirectoryDAO, null, null, null, mockGoogleIamDAO, null, mockGoogleProjectDAO, null, null, null, garbageOrgGoogleServicesConfig, petServiceAccountConfig, configResourceTypes)

    val defaultUserId = WorkbenchUserId("newuser123")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val defaultUser = WorkbenchUser(defaultUserId, Some(GoogleSubjectId(defaultUserId.value)), defaultUserEmail, None)

    val googleProject = GoogleProject("testproject")
    val report = intercept[WorkbenchExceptionWithErrorReport] {
      googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    }

    report.errorReport.statusCode shouldEqual Some(StatusCodes.BadRequest)
  }

  it should "return a failed IO when google returns a 403" in {
    val dirDAO = newDirectoryDAO()

    clearDatabase()

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleProjectDAO = new MockGoogleProjectDAO {
      override def getAncestry(projectName: String): Future[Seq[Ancestor]] = {
        Future.failed(new HttpResponseException.Builder(403, "Made up error message", new HttpHeaders()).build())
      }
    }
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, dirDAO, newRegistrationDAO(), null, mockGoogleDirectoryDAO, null, null, null, mockGoogleIamDAO, null, mockGoogleProjectDAO, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    val defaultUserId = WorkbenchUserId("newuser123")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val defaultUser = WorkbenchUser(defaultUserId, Some(GoogleSubjectId(defaultUserId.value)), defaultUserEmail, None)

    val googleProject = GoogleProject("testproject")
    val report = intercept[WorkbenchExceptionWithErrorReport] {
      googleExtensions.createUserPetServiceAccount(defaultUser, googleProject, samRequestContext).unsafeRunSync()
    }

    report.errorReport.statusCode shouldEqual Some(StatusCodes.BadRequest)
  }

  "fireAndForgetNotifications" should "not fail" in {
    val mockGoogleNotificationPubSubDAO = new MockGooglePubSubDAO
    val topicName = "neat_topic"
    val notificationDAO = new PubSubNotificationDAO(mockGoogleNotificationPubSubDAO, topicName)
    val googleExtensions = new GoogleExtensions(TestSupport.fakeDistributedLock, null, newRegistrationDAO(), null, null, mockGoogleNotificationPubSubDAO, null, null, null, null, null, null, notificationDAO, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    val messages = Set(
      Notifications.GroupAccessRequestNotification(
        WorkbenchUserId("Bob"),
        WorkbenchGroupName("bobs_buds").value,
        Set(WorkbenchUserId("reply_to_address")),
        WorkbenchUserId("requesters_id")
      ))

    googleExtensions.fireAndForgetNotifications(messages)

    val messageLog: ConcurrentLinkedQueue[String] = mockGoogleNotificationPubSubDAO.messageLog
    val formattedMessages: Set[String] = messages.map(m => topicName + "|" + NotificationFormat.write(m).toString())
    messageLog should contain theSameElementsAs formattedMessages
  }

  protected def clearDatabase(): Unit = {
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    TestSupport.truncateAll
  }
}
