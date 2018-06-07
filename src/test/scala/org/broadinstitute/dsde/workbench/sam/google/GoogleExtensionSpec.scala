package org.broadinstitute.dsde.workbench.sam.google

import java.util.{Date, UUID}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchExceptionWithErrorReport, _}
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, _}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, JndiDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, MockAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Try}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId, ServiceAccountName}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.mockito.ArgumentMatcher

class GoogleExtensionSpec(_system: ActorSystem) extends TestKit(_system) with FlatSpecLike with Matchers with TestSupport with MockitoSugar with ScalaFutures with BeforeAndAfterAll {
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
    val testPolicy = AccessPolicy(ResourceAndPolicyName(Resource(ResourceTypeName("rt"), ResourceId("rid")), AccessPolicyName("ap")), Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail, Set.empty, Set.empty)

    Seq(testGroup, testPolicy).foreach { target =>
      val mockAccessPolicyDAO = mock[AccessPolicyDAO]
      val mockDirectoryDAO = mock[DirectoryDAO]
      val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
      val mockGooglePubSubDAO = new MockGooglePubSubDAO
      val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, null, null, null,null, null, null, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id)).thenReturn(Future.successful(Option(testGroup)))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id)).thenReturn(Future.successful(Option(testPolicy)))
      }
      when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(()))
      when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(Some(new Date(2017, 11, 22))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("addError"))).thenReturn(Future.successful(Some(WorkbenchEmail(addErrorProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inSamUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inSamUserProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inGoogleUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inGoogleUserProxyEmail))))
      when(mockDirectoryDAO.readProxyGroup(WorkbenchUserId("inBothUser"))).thenReturn(Future.successful(Some(WorkbenchEmail(inBothUserProxyEmail))))

      val subGroups = Seq(inSamSubGroup, inGoogleSubGroup, inBothSubGroup)
      subGroups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id)).thenReturn(Future.successful(Option(g.email))) }

      val added = Seq(inSamSubGroup.email, WorkbenchEmail(inSamUserProxyEmail))
      val removed = Seq(inGoogleSubGroup.email, WorkbenchEmail(inGoogleUserProxyEmail))

      when(mockGoogleDirectoryDAO.listGroupMembers(target.email)).thenReturn(Future.successful(Option(Seq(WorkbenchEmail(inGoogleUserProxyEmail).value, WorkbenchEmail(inBothUserProxyEmail).value.toLowerCase, inGoogleSubGroup.email.value, inBothSubGroup.email.value, removeError))))
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
    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))
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
    val (dirDAO: JndiDirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, defaultUser: WorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

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

  private def initPetTest: (JndiDirectoryDAO, MockGoogleIamDAO, MockGoogleDirectoryDAO, GoogleExtensions, UserService, WorkbenchUserId, WorkbenchEmail, WorkbenchEmail, WorkbenchUser) = {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())
    runAndWait(schemaDao.createOrgUnits())

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))
    val service = new UserService(dirDAO, googleExtensions)

    val defaultUserId = WorkbenchUserId("newuser123")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    /* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val defaultUserProxyEmail = WorkbenchEmail(s"newuser_newuser123@${googleServicesConfig.appsDomain}")
*/
    val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_newuser123@${googleServicesConfig.appsDomain}")
    /**/
    val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)
    (dirDAO, mockGoogleIamDAO, mockGoogleDirectoryDAO, googleExtensions, service, defaultUserId, defaultUserEmail, defaultUserProxyEmail, defaultUser)
  }

  it should "attach existing service account to pet" in {
    val (dirDAO: JndiDirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, defaultUser: WorkbenchUser) = initPetTest
    val googleProject = GoogleProject("testproject")

    val (saName, saDisplayName) = googleExtensions.toPetSAFromUser(defaultUser)
    val serviceAccount = mockGoogleIamDAO.createServiceAccount(googleProject, saName, saDisplayName).futureValue
    // create a user

    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // create a pet service account
    val petServiceAccount = googleExtensions.createUserPetServiceAccount(defaultUser, googleProject).futureValue
    petServiceAccount.serviceAccount shouldBe serviceAccount

  }

  it should "recreate service account when missing for pet" in {
    val (dirDAO: JndiDirectoryDAO, mockGoogleIamDAO: MockGoogleIamDAO, mockGoogleDirectoryDAO: MockGoogleDirectoryDAO, googleExtensions: GoogleExtensions, service: UserService, defaultUserId: WorkbenchUserId, defaultUserEmail: WorkbenchEmail, defaultUserProxyEmail: WorkbenchEmail, defaultUser: WorkbenchUser) = initPetTest

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
    newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

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

    val ge = new GoogleExtensions(mockDirectoryDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))

    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(None))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe None

    val date = new Date()
    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(Some(date)))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe Some(date)
  }

  it should "throw an exception with a NotFound error report when getting sync date for group that does not exist" in {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, "local", configResourceTypes(CloudExtensions.resourceTypeName))
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(ge.getSynchronizedDate(groupName))
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return None when getting sync date for a group that has not been synced" in {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, null, null, null, googleServicesConfig, null, "local", configResourceTypes(CloudExtensions.resourceTypeName))
    val groupName = WorkbenchGroupName("group-sync")
    runAndWait(dirDAO.createGroup(BasicWorkbenchGroup(groupName, Set(), WorkbenchEmail(""))))
    try {
      runAndWait(ge.getSynchronizedDate(groupName)) shouldBe None
    } finally {
      runAndWait(dirDAO.deleteGroup(groupName))
    }
  }

  it should "return sync date for a group that has been synced" in {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, new MockGoogleDirectoryDAO(), null, null, null, null, null, null, null, googleServicesConfig, null, "local", configResourceTypes(CloudExtensions.resourceTypeName))
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

  it should "create google extension resource on boot" in {
    val mockAccessPolicyDAO = new MockAccessPolicyDAO
    val mockDirectoryDAO = new MockDirectoryDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGooglePubSubDAO = new MockGooglePubSubDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val mockGoogleIamDAO = new MockGoogleIamDAO
    val notificationDAO = new PubSubNotificationDAO(mockGooglePubSubDAO, "foo")
    val googleKeyCache = new GoogleKeyCache(mockGoogleIamDAO, mockGoogleStorageDAO, mockGooglePubSubDAO, googleServicesConfig, petServiceAccountConfig)

    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, mockGoogleStorageDAO, null, null, googleKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))

    val app = SamApplication(new UserService(mockDirectoryDAO, ge), new ResourceService(configResourceTypes, mockAccessPolicyDAO, mockDirectoryDAO, ge, "example.com"), null)
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), AccessPolicyName("owner"))

    runAndWait(mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId))) shouldBe None
    runAndWait(mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName)) shouldBe None

    runAndWait(ge.onBoot(app))

    runAndWait(mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId))) shouldBe Some(WorkbenchUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId), googleServicesConfig.serviceAccountClientEmail))
    runAndWait(mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName)).map(_.copy(email = null)) shouldBe Some(AccessPolicy(
      resourceAndPolicyName,
      Set(WorkbenchUserId(googleServicesConfig.serviceAccountClientId)),
      null,
      Set(ResourceRoleName("owner")),
      Set.empty
    ))

    // make sure a repeated call does not fail
    runAndWait(ge.onBoot(app))

  }

  it should "include username, subject ID, and apps domain in proxy group email" in {
    val appsDomain = "test.cloudfire.org"
    val subjectId = "0123456789"
    val username = "foo"

    val config = googleServicesConfig.copy(appsDomain = appsDomain)
    val googleExtensions = new GoogleExtensions(null, null, null, null, null, null, null, null, null, null, config, null, "local", null )

    val user = WorkbenchUser(WorkbenchUserId(subjectId), WorkbenchEmail(s"$username@test.org"))

    val proxyEmail = googleExtensions.toProxyFromUser(user).value
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
    val googleExtensions = new GoogleExtensions(null, null, null, null, null, null, null, null, null, null, config, null, "local", null)

    val user = WorkbenchUser(WorkbenchUserId("0123456789"), WorkbenchEmail("foo-bar-baz-qux-quux-corge-grault-garply@test.org"))

    val proxyEmail = googleExtensions.toProxyFromUser(user).value
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
    val user = WorkbenchUser(userId, userEmail)
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val proxyEmail = WorkbenchEmail(s"foo_$userId@${googleServicesConfig.appsDomain}")
*/
    val proxyEmail = WorkbenchEmail(s"PROXY_$userId@${googleServicesConfig.appsDomain}")
/**/

    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, null, mockGoogleDirectoryDAO, null, null, null, null, null, null, null, googleServicesConfig, null, "local", null)

    val allUsersGroup = BasicWorkbenchGroup(NoExtensions.allUsersGroupName, Set.empty, WorkbenchEmail(s"TEST_ALL_USERS_GROUP@test.firecloud.org"))
    val allUsersGroupMatcher = new ArgumentMatcher[BasicWorkbenchGroup] {
      override def matches(group: BasicWorkbenchGroup): Boolean = group.id == allUsersGroup.id
    }
    when(mockDirectoryDAO.createGroup(argThat(allUsersGroupMatcher))).thenReturn(Future.successful(allUsersGroup))

    when(mockDirectoryDAO.addProxyGroup(userId, proxyEmail)).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.getGoogleGroup(any[WorkbenchEmail])).thenReturn(Future.successful(None))
    when(mockGoogleDirectoryDAO.createGroup(any[String], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    googleExtensions.onUserCreate(user).futureValue

    verify(mockGoogleDirectoryDAO).createGroup(userEmail.value, proxyEmail)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(proxyEmail, userEmail)
    verify(mockGoogleDirectoryDAO).addMemberToGroup(allUsersGroup.email, proxyEmail)
/* Re-enable this code after fixing rawls for GAWB-2933
    verify(mockDirectoryDAO).addProxyGroup(userId, proxyEmail)
*/
  }


  it should "do Googley stuff onGroupDelete" in {
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
    val googleExtensions = new GoogleExtensions(mockDirectoryDAO, null, mockGoogleDirectoryDAO, null, null, null, null, null, null, null, googleServicesConfig, null, "local", null)

    val testPolicy = BasicWorkbenchGroup(WorkbenchGroupName("blahblahblah"), Set.empty, WorkbenchEmail(s"blahblahblah@test.firecloud.org"))

    when(mockDirectoryDAO.deleteGroup(testPolicy.id)).thenReturn(Future.successful(()))

    googleExtensions.onGroupDelete(testPolicy.email)

    verify(mockGoogleDirectoryDAO).deleteGroup(testPolicy.email)
  }

  private def setupGoogleKeyCacheTests: (GoogleExtensions, UserService) = {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
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

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, mockGoogleStorageDAO, null, null, googleKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, "local", configResourceTypes(CloudExtensions.resourceTypeName))
    val service = new UserService(dirDAO, googleExtensions)

    (googleExtensions, service)
  }

  "GoogleKeyCache" should "create a service account key and return the same key when called again" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val (googleExtensions, service) = setupGoogleKeyCacheTests

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
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
    val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
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
    val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)

    // create a user
    val newUser = service.createUser(defaultUser).futureValue
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
}
