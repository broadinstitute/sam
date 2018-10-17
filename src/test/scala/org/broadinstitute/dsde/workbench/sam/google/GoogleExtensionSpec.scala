package org.broadinstitute.dsde.workbench.sam.google

import java.net.URI
import java.util.{Date, GregorianCalendar, UUID}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.testkit.TestKit
import com.google.api.services.groupssettings.model.Groups
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{WorkbenchExceptionWithErrorReport, _}
import org.broadinstitute.dsde.workbench.sam.api.CreateWorkbenchUser
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, GoogleServicesConfig, PetServiceAccountConfig, _}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, LdapDirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LdapAccessPolicyDAO, MockAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model, _}
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers, PrivateMethodTester}
import org.broadinstitute.dsde.workbench.sam.TestSupport.blockingEc
import cats.effect.IO

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Try}

class GoogleExtensionSpec(_system: ActorSystem) extends TestKit(_system) with FlatSpecLike with Matchers with TestSupport with MockitoSugar with ScalaFutures with BeforeAndAfterAll with PrivateMethodTester {
  def this() = this(ActorSystem("GoogleGroupSyncMonitorSpec"))

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  lazy val config = ConfigFactory.load()
  lazy val directoryConfig = config.as[DirectoryConfig]("directory")
  lazy val dirURI = new URI(directoryConfig.directoryUrl)
  lazy val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  lazy val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  val configResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.map(rt => rt.name -> rt).toMap
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
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val inSamUserProxyEmail = "foo_inSamUser@test.firecloud.org"
*/
    val inSamUserProxyEmail = s"PROXY_inSamUser@${googleServicesConfig.appsDomain}"
/**/
    val inGoogleUserId = WorkbenchUserId("inGoogleUser")
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val inGoogleUserProxyEmail = "foo_inGoogleUser@test.firecloud.org"
*/
    val inGoogleUserProxyEmail = s"PROXY_inGoogleUser@${googleServicesConfig.appsDomain}"
/**/
    val inBothUserId = WorkbenchUserId("inBothUser")
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val inBothUserProxyEmail = "foo_inBothUser@test.firecloud.org"
*/
    val inBothUserProxyEmail = s"PROXY_inBothUser@${googleServicesConfig.appsDomain}"
/**/

    val addError = WorkbenchUserId("addError")
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val addErrorProxyEmail = "foo_addError@test.firecloud.org"
*/
    val addErrorProxyEmail = s"PROXY_addError@${googleServicesConfig.appsDomain}"
/**/
    val removeError = "removeError@foo.bar"

    val testGroup = BasicWorkbenchGroup(groupName, Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail)
    val testPolicy = AccessPolicy(
      model.FullyQualifiedPolicyId(
        FullyQualifiedResourceId(ResourceTypeName("workspace"), ResourceId("rid")), AccessPolicyName("ap")), Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail, Set.empty, Set.empty, public = true)

    Seq(testGroup, testPolicy).foreach { target =>
      val mockAccessPolicyDAO = mock[AccessPolicyDAO]
      val mockDirectoryDAO = mock[DirectoryDAO]
      val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
      val mockGooglePubSubDAO = new MockGooglePubSubDAO
      val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, null, null,null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id)).thenReturn(Future.successful(Option(testGroup)))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id)).thenReturn(IO.pure(Option(testPolicy)))
      }
      when(mockDirectoryDAO.loadGroup(ge.allUsersGroupName)).thenReturn(Future.successful(Option(BasicWorkbenchGroup(ge.allUsersGroupName, Set.empty, ge.allUsersGroupEmail))))
      when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(()))
      when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(Some((new GregorianCalendar(2017, 11, 22).getTime()))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("addError"))).thenReturn(Future.successful(Some(WorkbenchEmail(addErrorProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inSamUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inSamUserProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inGoogleUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inGoogleUserProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inBothUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inBothUserProxyEmail))))

      val subGroups = Seq(inSamSubGroup, inGoogleSubGroup, inBothSubGroup)
      subGroups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id)).thenReturn(Future.successful(Option(g.email))) }
      when(mockDirectoryDAO.loadSubjectEmail(ge.allUsersGroupName)).thenReturn(Future.successful(Option(ge.allUsersGroupEmail)))

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

      val results = runAndWait(ge.synchronizeGroupMembers(target.id))

      results.head._1 should equal(target.email)
      results.head._2 should contain theSameElementsAs (
        added.map(e => SyncReportItem("added", e.value.toLowerCase, None)) ++
          removed.map(e => SyncReportItem("removed", e.value.toLowerCase, None)) ++
          Seq(
            SyncReportItem("added", addErrorProxyEmail.toLowerCase, Option(ErrorReport(addException))),
            SyncReportItem("removed", removeError.toLowerCase, Option(ErrorReport(removeException)))))

      added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(target.email, WorkbenchEmail(email.value.toLowerCase)) }
      removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(target.email, WorkbenchEmail(email.value.toLowerCase)) }
      verify(mockDirectoryDAO).updateSynchronizedDate(target.id)
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
    val testPolicy = AccessPolicy(rpn, Set(policyOnlySamUserId, intersectionSamUserId, authorizedGoogleUserId, policyOnlySamSubGroup.id, intersectionSamSubGroup.id, authorizedGoogleSubGroup.id, addError), WorkbenchEmail("testPolicy@example.com"), Set(constrainableRole.roleName), constrainableRole.actions, public = false)

    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, null, null, null,null, null, null, googleServicesConfig, petServiceAccountConfig, constrainableResourceTypes)

    when(mockAccessPolicyDAO.loadPolicy(testPolicy.id)).thenReturn(IO.pure(Option(testPolicy)))
    when(mockAccessPolicyDAO.loadResourceAuthDomain(resource.fullyQualifiedId)).thenReturn(IO.pure(Set(managedGroupId)))

    when(mockDirectoryDAO.listIntersectionGroupUsers(Set(managedGroupId, testPolicy.id))).thenReturn(Future.successful(Set(intersectionSamUserId, authorizedGoogleUserId, subIntersectionSamGroupUserId, subAuthorizedGoogleGroupUserId, addError)))

    when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(()))
    when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(Some(new GregorianCalendar(2017, 11, 22).getTime())))

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

    val results = runAndWait(ge.synchronizeGroupMembers(testPolicy.id))

    results.head._1 should equal(testPolicy.email)
    results.head._2 should contain theSameElementsAs (
      added.map(e => SyncReportItem("added", e.value.toLowerCase, None)) ++
        removed.map(e => SyncReportItem("removed", e.value.toLowerCase, None)) ++
        Seq(
          SyncReportItem("added", addErrorProxyEmail.toLowerCase, Option(ErrorReport(addException))),
          SyncReportItem("removed", removeError.toLowerCase, Option(ErrorReport(removeException)))))

    added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)) }
    removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(testPolicy.email, WorkbenchEmail(email.value.toLowerCase)) }
    verify(mockDirectoryDAO).updateSynchronizedDate(testPolicy.id)
  }

  it should "break out of cycle" in {
    val groupName = WorkbenchGroupName("group1")
    val groupEmail = WorkbenchEmail("group1@example.com")
    val subGroupName = WorkbenchGroupName("group2")
    val subGroupEmail = WorkbenchEmail("group2@example.com")

    val subGroup = BasicWorkbenchGroup(subGroupName, Set.empty, subGroupEmail)
    val topGroup = BasicWorkbenchGroup(groupName, Set.empty, groupEmail)

    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    //create groups
    runAndWait(mockDirectoryDAO.createGroup(topGroup))
    runAndWait(mockDirectoryDAO.createGroup(subGroup))
    //add subGroup to topGroup
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(groupEmail, subGroupEmail))
    runAndWait(mockDirectoryDAO.addGroupMember(topGroup.id, subGroup.id))
    //add topGroup to subGroup - creating cycle
    runAndWait(mockGoogleDirectoryDAO.addMemberToGroup(subGroupEmail, groupEmail))
    runAndWait(mockDirectoryDAO.addGroupMember(subGroup.id, topGroup.id))
    when(mockGoogleDirectoryDAO.listGroupMembers(topGroup.email)).thenReturn(Future.successful(Option(Seq(subGroupEmail.value))))
    when(mockGoogleDirectoryDAO.listGroupMembers(subGroup.email)).thenReturn(Future.successful(Option(Seq(groupEmail.value))))
    val syncedEmails = runAndWait(ge.synchronizeGroupMembers(topGroup.id)).keys
    syncedEmails shouldEqual Set(groupEmail, subGroupEmail)
  }

  "GoogleExtension" should "get a pet service account for a user" in {
    val (dirDAO: DirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    val defaultUser = WorkbenchUser(createDefaultUser.id, Some(createDefaultUser.googleSubjectId), createDefaultUser.email)
    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue

    petServiceAccount.serviceAccount.email.value should endWith(s"@${googleProject.value}.iam.gserviceaccount.com")

    // verify ldap
    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUserId, googleProject)).futureValue shouldBe Some(petServiceAccount)

    val ldapPetOpt = dirDAO.loadSubjectFromEmail(petServiceAccount.serviceAccount.email).flatMap {
      case Some(subject: PetServiceAccountId) => dirDAO.loadPetServiceAccount(subject)
      case _ => fail(s"could not load pet LDAP entry from ${petServiceAccount.serviceAccount.email.value}")
    }.futureValue

    ldapPetOpt shouldBe 'defined
    val Some(ldapPet) = ldapPetOpt
    // MockGoogleIamDAO generates the subject ID as a random Long
    Try(ldapPet.serviceAccount.subjectId.value.toLong) shouldBe a[Success[_]]

    // verify google
    mockGoogleIamDAO.serviceAccounts should contain key petServiceAccount.serviceAccount.email
    mockGoogleDirectoryDAO.groups should contain key defaultUserProxyEmail
    mockGoogleDirectoryDAO.groups(defaultUserProxyEmail) shouldBe Set(defaultUserEmail, petServiceAccount.serviceAccount.email)

    // create one again, it should work
    val petSaResponse2 = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue
    petSaResponse2 shouldBe petServiceAccount

    // delete the pet service account
    googleExtensions.deleteUserPetServiceAccount(newUser.userInfo.userSubjectId, googleProject).futureValue shouldBe true

    // the user should still exist in LDAP
    dirDAO.loadUser(defaultUserId).futureValue shouldBe Some(defaultUser)

    // the pet should not exist in LDAP
    dirDAO.loadPetServiceAccount(PetServiceAccountId(defaultUserId, googleProject)).futureValue shouldBe None

    // the pet should not exist in Google
    mockGoogleIamDAO.serviceAccounts should not contain key (petServiceAccount.serviceAccount.email)

  }

  private def initPetTest: (DirectoryDAO, MockGoogleIamDAO, MockGoogleDirectoryDAO, GoogleExtensions, UserService, WorkbenchUserId, WorkbenchEmail, WorkbenchEmail, CreateWorkbenchUser) = {
    val dirURI = new URI(directoryConfig.directoryUrl)
    val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    val service = new UserService(dirDAO, googleExtensions)

    val defaultUserId = WorkbenchUserId("newuser123")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    /* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val defaultUserProxyEmail = WorkbenchEmail(s"newuser_newuser123@${googleServicesConfig.appsDomain}")
*/
    val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_newuser123@${googleServicesConfig.appsDomain}")
    /**/
    val defaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail)
    (dirDAO, mockGoogleIamDAO, mockGoogleDirectoryDAO, googleExtensions, service, defaultUserId, defaultUserEmail, defaultUserProxyEmail, defaultUser)
  }

  it should "attach existing service account to pet" in {
    val (dirDAO: DirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest
    val googleProject = GoogleProject("testproject")

    val defaultUser = WorkbenchUser(createDefaultUser.id, None, createDefaultUser.email)
    val (saName, saDisplayName) = googleExtensions.toPetSAFromUser(defaultUser)
    val serviceAccount = mockGoogleIamDAO.createServiceAccount(googleProject, saName, saDisplayName).futureValue
    // create a user

    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue
    petServiceAccount.serviceAccount shouldBe serviceAccount

  }

  it should "recreate service account when missing for pet" in {
    val (dirDAO: DirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, createDefaultUser: CreateWorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    val defaultUser = WorkbenchUser(createDefaultUser.id, None, createDefaultUser.email)
    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue

    import org.broadinstitute.dsde.workbench.model.google.toAccountName
    mockGoogleIamDAO.removeServiceAccount(googleProject, toAccountName(petServiceAccount.serviceAccount.email)).futureValue
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe None

    val petServiceAccount2 = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue
    petServiceAccount.serviceAccount shouldNot equal(petServiceAccount2.serviceAccount)
    mockGoogleIamDAO.findServiceAccount(googleProject, petServiceAccount.serviceAccount.email).futureValue shouldBe Some(petServiceAccount2.serviceAccount)
  }

  it should "get a group's last synchronized date" in {
    val groupName = WorkbenchGroupName("group1")

    val mockDirectoryDAO = mock[DirectoryDAO]

    val ge = new GoogleExtensions(mockDirectoryDAO, null, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(None))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe None

    val date = new Date()
    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(Some(date)))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe Some(date)
  }

  it should "throw an exception with a NotFound error report when getting sync date for group that does not exist" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(ge.getSynchronizedDate(groupName))
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return None when getting sync date for a group that has not been synced" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail(""))))
    try {
      runAndWait(ge.getSynchronizedDate(groupName)) shouldBe None
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "return sync date for a group that has been synced" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, new MockGoogleDirectoryDAO(), null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail("group1@test.firecloud.org"))))
    try {
      runAndWait(ge.synchronizeGroupMembers(groupName))
      val syncDate = runAndWait(ge.getSynchronizedDate(groupName)).get
      syncDate.getTime should equal (new Date().getTime +- 1.second.toMillis)
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "throw an exception with a NotFound error report when getting email for group that does not exist" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(ge.getSynchronizedEmail(groupName))
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return email for a group" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email)))
    try {
      runAndWait(ge.getSynchronizedEmail(groupName)) shouldBe Some(email)
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "return None if an email is found, but the group has not been synced" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email)))
    try {
      runAndWait(ge.getSynchronizedState(groupName)) shouldBe None
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "return SyncState with email and last sync date if there is an email and the group has been synced" in {
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, new MockGoogleDirectoryDAO(), null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)
    val groupName = WorkbenchGroupName("group-sync")
    val email = WorkbenchEmail("foo@bar.com")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), email)))
    try {
      runAndWait(ge.getSynchronizedState(groupName)) should equal(None)
      runAndWait(ge.synchronizeGroupMembers(groupName))
      val maybeSyncResponse = runAndWait(ge.getSynchronizedState(groupName))
      maybeSyncResponse.map(_.email) should equal(Some(email))
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "create google extension resource on boot" in {
    val mockAccessPolicyDAO = new MockAccessPolicyDAO
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val notificationDAO = new PubSubNotificationDAO(mockGooglePubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(mockGoogleIamDAO, mockGoogleStorageDAO, mockGooglePubSubDAO, googleServicesConfig, petServiceAccountConfig)

    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, mockGoogleStorageDAO, null, googleKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes)

    val app = SamApplication(new UserService(mockDirectoryDAO, ge), new ResourceService(configResourceTypes, null, mockAccessPolicyDAO, mockDirectoryDAO, ge, "example.com"), null)
    val resourceAndPolicyName = FullyQualifiedPolicyId(FullyQualifiedResourceId(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), AccessPolicyName("owner"))

    runAndWait(mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId))) shouldBe None
    mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName).unsafeRunSync() shouldBe None

    runAndWait(ge.onBoot(app))

    val uid = runAndWait(mockDirectoryDAO.loadSubjectFromGoogleSubjectId(GoogleSubjectId(googleServicesConfig.serviceAccountClientId))).get.asInstanceOf[WorkbenchUserId]
    val owner = runAndWait(mockDirectoryDAO.loadUser(uid)).get
    owner.googleSubjectId shouldBe Some(GoogleSubjectId(googleServicesConfig.serviceAccountClientId))
    owner.email shouldBe googleServicesConfig.serviceAccountClientEmail
    val res = mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName).unsafeRunSync().get
    res.id shouldBe resourceAndPolicyName
    res.members shouldBe Set(owner.id)
    res.roles shouldBe Set(ResourceRoleName("owner"))
    // make sure a repeated call does not fail
    runAndWait(ge.onBoot(app))

  }

  it should "include username, subject ID, and apps domain in proxy group email" in {
    val appsDomain = "test.cloudfire.org"
    val subjectId = "0123456789"
    val username = "foo"

    val config = googleServicesConfig.copy(appsDomain = appsDomain)
    val googleExtensions = new GoogleExtensions(null, null, null, null, null, null, null, null, null, config, null, configResourceTypes)

    val user = WorkbenchUser(WorkbenchUserId(subjectId), None, WorkbenchEmail(s"$username@test.org"))

    val proxyEmail = googleExtensions.toProxyFromUser(user.id).value
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    proxyEmail shouldBe "foo_0123456789@test.cloudfire.org"
    proxyEmail should include (username)
    proxyEmail should include (subjectId)
    proxyEmail should include (appsDomain)
*/
    proxyEmail shouldBe "PROXY_0123456789@test.cloudfire.org"
/**/
  }

  it should "truncate username if proxy group email would otherwise be too long" in {
    val config = googleServicesConfig.copy(appsDomain = "test.cloudfire.org")
    val googleExtensions = new GoogleExtensions(null, null, null, null, null, null, null, null, null, config, null, configResourceTypes)

    val user = WorkbenchUser(WorkbenchUserId("0123456789"), None, WorkbenchEmail("foo-bar-baz-qux-quux-corge-grault-garply@test.org"))

    val proxyEmail = googleExtensions.toProxyFromUser(user.id).value
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    proxyEmail shouldBe "foo-bar-baz-qux-quux-corge-grault-_0123456789@test.cloudfire.org"
    proxyEmail should have length 64
*/
    proxyEmail shouldBe "PROXY_0123456789@test.cloudfire.org"
/**/
  }

  it should "do Googley stuff onUserCreate" in {
    val userId = WorkbenchUserId(UUID.randomUUID().toString)
    val userEmail = WorkbenchEmail("foo@test.org")
    val user = WorkbenchUser(userId, None, userEmail)
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val proxyEmail = WorkbenchEmail(s"foo_$userId@${googleServicesConfig.appsDomain}")
*/
    val proxyEmail = WorkbenchEmail(s"PROXY_$userId@${googleServicesConfig.appsDomain}")
/**/

    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, null, mockGoogleDirectoryDAO, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val allUsersGroup = BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, WorkbenchEmail(s"TEST_ALL_USERS_GROUP@test.firecloud.org"))
    val allUsersGroupMatcher = new ArgumentMatcher[BasicWorkbenchGroup] {
      override def matches(group: BasicWorkbenchGroup): Boolean = group.id == allUsersGroup.id
    }
    when(mockDirectoryDAO.createGroup(argThat(allUsersGroupMatcher), isNull())).thenReturn(Future.successful(allUsersGroup))

    when(mockDirectoryDAO.addProxyGroup(userId, proxyEmail)).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.getGoogleGroup(any[WorkbenchEmail])).thenReturn(Future.successful(None))
    when(mockGoogleDirectoryDAO.createGroup(any[String], any[WorkbenchEmail], any[Option[Groups]])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    googleExtensions.onUserCreate(user).futureValue

    val lockedDownGroupSettings = Option(mockGoogleDirectoryDAO.lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).createGroup(userEmail.value, proxyEmail, lockedDownGroupSettings)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(proxyEmail, userEmail)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(allUsersGroup.email, proxyEmail)
/* Re-enable this code after fixing rawls for GAWB-2933
    verify(mockDirectoryDAO).addProxyGroup(userId, proxyEmail)
*/
  }

  it should "do Googley stuff onGroupDelete" in {
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, null, mockGoogleDirectoryDAO, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val testPolicy = BasicWorkbenchGroup(WorkbenchGroupName("blahblahblah"), Set.empty, WorkbenchEmail(s"blahblahblah@test.firecloud.org"))

    when(mockDirectoryDAO.deleteGroup(testPolicy.id)).thenReturn(Future.successful(()))

    googleExtensions.onGroupDelete(testPolicy.email)

    verify(mockGoogleDirectoryDAO).deleteGroup(testPolicy.email)
  }

  "onGroupUpdate" should "trigger updates to constrained policies if updating a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockGooglePubSubDAO = mock[MockGooglePubSubDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, null, mockGooglePubSubDAO, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"

    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), AccessPolicyName("managedGroup"))
    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGooglePubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId))).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId)).thenReturn(IO.pure(Set(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(managedGroupRPN)))

    // once when updating the managed group, and once when updating the policies it constrains
    verify(mockGooglePubSubDAO, times(2)).publishMessages(any[String], any[Seq[String]])
  }

  it should "trigger updates to constrained policies when updating a group that is a part of a managed group" in {
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockGooglePubSubDAO = mock[MockGooglePubSubDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, null, mockGooglePubSubDAO, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), AccessPolicyName("managedGroup"))

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGooglePubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish subgroup relationship to managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId))).thenReturn(Future.successful(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId))).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId)).thenReturn(IO.pure(Set(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId))))

    // once when updating the subgroup, and once when updating the policies constrained by the managed group
    verify(mockGooglePubSubDAO, times(2)).publishMessages(any[String], any[Seq[String]])
  }

  it should "break out of the loop" in {
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockGooglePubSubDAO = mock[MockGooglePubSubDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, null, mockGooglePubSubDAO, null, null, null, null, null, googleServicesConfig, null, configResourceTypes)

    val managedGroupId = "managedGroupId"
    val subGroupId = "subGroupId"
    val managedGroupRPN = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(ResourceTypeName("managed-group"), ResourceId(managedGroupId)), AccessPolicyName("managedGroup"))

    val resource = Resource(ResourceTypeName("resource"), ResourceId("rid"), Set(WorkbenchGroupName(managedGroupId)))
    val ownerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("owner"))
    val readerRPN = FullyQualifiedPolicyId(resource.fullyQualifiedId, AccessPolicyName("reader"))
    val ownerPolicy = AccessPolicy(ownerRPN, Set.empty, WorkbenchEmail("owner@example.com"), Set.empty, Set.empty, public = false)
    val readerPolicy = AccessPolicy(readerRPN, Set.empty, WorkbenchEmail("reader@example.com"), Set.empty, Set.empty, public = false)

    // mock responses for onGroupUpdate
    when(mockDirectoryDAO.listAncestorGroups(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Set.empty.asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.getSynchronizedDate(any[FullyQualifiedPolicyId])).thenReturn(Future.successful(Some(new GregorianCalendar(2018, 8, 26).getTime())))
    when(mockGooglePubSubDAO.publishMessages(any[String], any[Seq[String]])).thenReturn(Future.successful(()))

    // mock ancestor call to establish nested group structure for owner policy and subgroup in managed group
    when(mockDirectoryDAO.listAncestorGroups(WorkbenchGroupName(subGroupId))).thenReturn(Future.successful(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))
    when(mockDirectoryDAO.listAncestorGroups(ownerRPN)).thenReturn(Future.successful(Set(managedGroupRPN).asInstanceOf[Set[WorkbenchGroupIdentity]]))

    // mock responses for onManagedGroupUpdate
    when(mockAccessPolicyDAO.listResourcesConstrainedByGroup(WorkbenchGroupName(managedGroupId))).thenReturn(IO.pure(Set(resource)))
    when(mockAccessPolicyDAO.listAccessPolicies(resource.fullyQualifiedId)).thenReturn(IO.pure(Set(ownerPolicy, readerPolicy)))

    runAndWait(googleExtensions.onGroupUpdate(Seq(WorkbenchGroupName(subGroupId))))

    // once when updating the subgroup, and once when updating the policies constrained by the managed group
    verify(mockGooglePubSubDAO, times(2)).publishMessages(any[String], any[Seq[String]])
  }

  private def setupGoogleKeyCacheTests: (GoogleExtensions, UserService) = {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val notificationDAO = new PubSubNotificationDAO(mockGooglePubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(mockGoogleIamDAO, mockGoogleStorageDAO, mockGooglePubSubDAO, googleServicesConfig, petServiceAccountConfig)

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, mockGoogleStorageDAO, null, googleKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes)
    val service = new UserService(dirDAO, googleExtensions)

    (googleExtensions, service)
  }

  "GoogleKeyCache" should "create a service account key and return the same key when called again" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val createDefaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail)
    val defaultUser = WorkbenchUser(defaultUserId, None, defaultUserEmail)

    // create a user
    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue

    //get a key, which should create a brand new one
    val firstKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    //get a key again, which should return the original cached key created above
    val secondKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    assert(firstKey == secondKey)
  }

  it should "remove an existing key and then return a brand new one" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val createDefaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail)
    val defaultUser = WorkbenchUser(defaultUserId, None, defaultUserEmail)

    // create a user
    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue

    //get a key, which should create a brand new one
    val firstKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    //remove the key we just created
    runAndWait(for {
      keys <- googleExtensions.googleIamDAO.listServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email)
      _ <- Future.traverse(keys) { key =>
        googleExtensions.removePetServiceAccountKey(defaultUserId, googleProject, key.id)
      }
    } yield ())

    //get a key again, which should once again create a brand new one because we've deleted the cached one
    val secondKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    assert(firstKey != secondKey)
  }

  it should "clean up unknown pet SA keys" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val createDefaultUser = CreateWorkbenchUser(defaultUserId, GoogleSubjectId(defaultUserId.value), defaultUserEmail)
    val defaultUser = WorkbenchUser(defaultUserId, None, defaultUserEmail)

    // create a user
    val newUser = service.createUser(createDefaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val googleProject = GoogleProject("testproject")
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue

    //get a key, which should create a brand new one
    val firstKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    //remove the key we just created behind the scenes
    val removedKeyObjects = runAndWait(for {
      keyObjects <- googleExtensions.googleStorageDAO.listObjectsWithPrefix(googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName, googleExtensions.googleKeyCache.keyNamePrefix(googleProject, petServiceAccount.serviceAccount.email))
      _ <- Future.traverse(keyObjects) { keyObject =>
        googleExtensions.googleStorageDAO.removeObject(googleExtensions.googleServicesConfig.googleKeyCacheConfig.bucketName, keyObject)
      }
    } yield (keyObjects))

    // assert that keys still exist on service account
    assert(removedKeyObjects.forall { removed =>
      val existingKeys = runAndWait(googleExtensions.googleIamDAO.listUserManagedServiceAccountKeys(googleProject, petServiceAccount.serviceAccount.email))
      existingKeys.exists(key => removed.value.endsWith(key.id.value))
    })

    //get a key again, which should once again create a brand new one because we've deleted the cached one
    //and all the keys removed should have been removed from google
    val secondKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

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
  private def initPrivateTest: (DirectoryDAO, GoogleExtensions, ResourceService, ManagedGroupService, ResourceType, ResourceRole) = {
    implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
    //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
    val realResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap
    val managedGroupResourceType = realResourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

    val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
    val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
    val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc)
    val schemaDAO = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    runAndWait(schemaDAO.clearDatabase())
    runAndWait(schemaDAO.init())
    runAndWait(schemaDAO.createOrgUnits())

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

    val googleExtensions = new GoogleExtensions(dirDAO, policyDAO, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, constrainableResourceTypes)
    val constrainablePolicyEvaluatorService = PolicyEvaluatorService(constrainableResourceTypes, policyDAO)
    val constrainableService = new ResourceService(constrainableResourceTypes, constrainablePolicyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
    val managedGroupService = new ManagedGroupService(constrainableService, constrainablePolicyEvaluatorService, constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync
    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync

    (dirDAO, googleExtensions, constrainableService, managedGroupService, constrainableResourceType, constrainableRole)
  }

  "calculateIntersectionGroup" should "find the intersection of the resource auth domain and the policy" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val inAuthDomainUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inAuthDomainUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainUser.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail)))

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothUser.userId))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), inBothUser.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), Set.empty, Set.empty)))

    val calculateIntersectionGroup = PrivateMethod[Future[Set[WorkbenchUserId]]]('calculateIntersectionGroup)
    val intersectionGroup = runAndWait(ge invokePrivate calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy))
    intersectionGroup shouldEqual Set(inBothUser.userId)
  }

  it should "handle nested group structures for policies and auth domains" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    // User in owner policy of both auth domain and resource to be used during creation of the managed group and resource
    val superAdminOwner = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("authDomainOwner"), WorkbenchEmail("authDomainOwner@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(superAdminOwner.userId, Some(TestSupport.genGoogleSubjectId()), superAdminOwner.userEmail)))

    // User in subgroup within auth domain; will not be in intersection group
    val inAuthDomainSubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inAuthDomainSubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainSubGroupUser.userEmail)))

    // User in subgroup within policy; will not be in intersection group
    val inPolicySubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inPolicySubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicySubGroupUser.userEmail)))

    // User in subgroup within both policy and auth domain; will be in intersection group
    val inBothSubGroupUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inBothSubGroupUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothSubGroupUser.userEmail)))

    // Create subgroups as groups in ldap
    val inAuthDomainSubGroup = runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inAuthDomainSubGroup"), Set(inAuthDomainSubGroupUser.userId), WorkbenchEmail("imAuthDomain@subGroup.com"))))
    val inPolicySubGroup = runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inPolicySubGroup"), Set(inPolicySubGroupUser.userId), WorkbenchEmail("inPolicy@subGroup.com"))))
    val inBothSubGroup = runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set(inBothSubGroupUser.userId), WorkbenchEmail("inBoth@subGroup.com"))))

    // Create managed group to act as auth domain and add appropriate subgroups
    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), superAdminOwner))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inAuthDomainSubGroup.id))
    runAndWait(managedGroupService.addSubjectToPolicy(ResourceId(managedGroupId), ManagedGroupService.memberPolicyName, inBothSubGroup.id))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(superAdminOwner.userEmail), Set.empty, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), superAdminOwner.userId))

    // Access policy that intersection group will be calculated for
    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicySubGroup.email, inBothSubGroup.email), Set.empty, Set.empty)))

    val calculateIntersectionGroup = PrivateMethod[Future[Set[WorkbenchUserId]]]('calculateIntersectionGroup)
    val intersectionGroup = runAndWait(ge invokePrivate calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy))
    intersectionGroup shouldEqual Set(inBothSubGroupUser.userId)
  }

  it should "return the policy members if there is no auth domain set" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, inBothUser.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail, inBothUser.userEmail), Set.empty, Set.empty)))

    val calculateIntersectionGroup = PrivateMethod[Future[Set[WorkbenchUserId]]]('calculateIntersectionGroup)
    val intersectionGroup = runAndWait(ge invokePrivate calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy))
    intersectionGroup shouldEqual Set(inBothUser.userId, inPolicyUser.userId)
  }

  it should "return an empty set if none of the policy members are in the auth domain" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val inAuthDomainUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inAuthDomain"), WorkbenchEmail("inAuthDomain@example.com"), 0)
    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)

    runAndWait(dirDAO.createUser(WorkbenchUser(inAuthDomainUser.userId, Some(TestSupport.genGoogleSubjectId()), inAuthDomainUser.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail)))

    val managedGroupId = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupId), inAuthDomainUser))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set(WorkbenchGroupName(managedGroupId)), inAuthDomainUser.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set(inPolicyUser.userEmail), Set.empty, Set.empty)))

    val calculateIntersectionGroup = PrivateMethod[Future[Set[WorkbenchUserId]]]('calculateIntersectionGroup)
    val intersectionGroup = runAndWait(ge invokePrivate calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy))
    intersectionGroup shouldEqual Set.empty
  }

  it should "return an empty set if both the auth domain and the policy are empty" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, managedGroupService: ManagedGroupService, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val inPolicyUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inPolicy"), WorkbenchEmail("inPolicy@example.com"), 0)
    val inBothUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("inBoth"), WorkbenchEmail("inBoth@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(inPolicyUser.userId, Some(TestSupport.genGoogleSubjectId()), inPolicyUser.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(inBothUser.userId, Some(TestSupport.genGoogleSubjectId()), inBothUser.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(inPolicyUser.userEmail), constrainableRole.actions, Set(constrainableRole.roleName)),
      AccessPolicyName("emptyPolicy") -> AccessPolicyMembership(Set.empty, Set.empty, Set.empty))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, inBothUser.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty)))

    val calculateIntersectionGroup = PrivateMethod[Future[Set[WorkbenchUserId]]]('calculateIntersectionGroup)
    val intersectionGroup = runAndWait(ge invokePrivate calculateIntersectionGroup(resource.fullyQualifiedId, accessPolicy))
    intersectionGroup shouldEqual Set.empty
  }

  "isConstrainable" should "return true when the policy has constrainable actions and roles" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, dummyUserInfo.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, constrainableRole.actions, Set(constrainableRole.roleName))))

    val isConstrainable = PrivateMethod[Boolean]('isConstrainable)
    val constrained = ge invokePrivate isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable role, but no constrainable actions" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, dummyUserInfo.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set(constrainableRole.roleName))))

    val isConstrainable = PrivateMethod[Boolean]('isConstrainable)
    val constrained = ge invokePrivate isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return true when the policy has a constrainable action, but no constrainable roles" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, dummyUserInfo.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, constrainableRole.actions, Set.empty)))

    val isConstrainable = PrivateMethod[Boolean]('isConstrainable)
    val constrained = ge invokePrivate isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual true
  }

  it should "return false when the policy is not constrainable" in {
    val (dirDAO: DirectoryDAO, ge: GoogleExtensions, constrainableService: ResourceService, _, constrainableResourceType: ResourceType, constrainableRole: ResourceRole) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))

    val accessPolicyMap = Map(AccessPolicyName(constrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(constrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, dummyUserInfo.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(constrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty)))

    val isConstrainable = PrivateMethod[Boolean]('isConstrainable)
    val constrained = ge invokePrivate isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }

  it should "return false when the resource type is not constrainable" in {
    val (dirDAO: DirectoryDAO, _, constrainableService: ResourceService, _, _, _) = initPrivateTest

    val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userId"), WorkbenchEmail("userId@example.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))

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

    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, nonConstrainableResourceTypes)

    constrainableService.createResourceType(nonConstrainableResourceType).unsafeRunSync

    val accessPolicyMap = Map(AccessPolicyName(nonConstrainableRole.roleName.value) -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), nonConstrainableRole.actions, Set(nonConstrainableRole.roleName)))
    val resource = runAndWait(constrainableService.createResource(nonConstrainableResourceType, ResourceId("rid"), accessPolicyMap, Set.empty, dummyUserInfo.userId))

    val accessPolicy = runAndWait(constrainableService.overwritePolicy(nonConstrainableResourceType, AccessPolicyName("ap"), resource.fullyQualifiedId, AccessPolicyMembership(Set.empty, Set.empty, Set.empty)))

    val isConstrainable = PrivateMethod[Boolean]('isConstrainable)
    val constrained = ge invokePrivate isConstrainable(resource.fullyQualifiedId, accessPolicy)
    constrained shouldEqual false
  }
}
