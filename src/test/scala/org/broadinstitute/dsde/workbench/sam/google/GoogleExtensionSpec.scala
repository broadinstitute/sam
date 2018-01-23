package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream
import java.util.Date

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import com.google.api.services.storage.model.StorageObject
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model._
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
import org.scalatest.{BeforeAndAfterAll, FlatSpec, FlatSpecLike, Matchers}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Success, Try}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

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
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  val configResourceTypes = config.as[Set[ResourceType]]("resourceTypes").map(rt => rt.name -> rt).toMap

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
    val inGoogleUserId = WorkbenchUserId("inGoogleUser")
    val inBothUserId = WorkbenchUserId("inBothUser")

    val addError = WorkbenchUserId("addError")
    val removeError = "removeError@foo.bar"

    val testGroup = BasicWorkbenchGroup(groupName, Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail)
    val testPolicy = AccessPolicy(ResourceAndPolicyName(Resource(ResourceTypeName("rt"), ResourceId("rid")), AccessPolicyName("ap")), Set(inSamSubGroup.id, inBothSubGroup.id, inSamUserId, inBothUserId, addError), groupEmail, Set.empty, Set.empty)

    Seq(testGroup, testPolicy).foreach { target =>
      val mockAccessPolicyDAO = mock[AccessPolicyDAO]
      val mockDirectoryDAO = mock[DirectoryDAO]
      val mockGoogleDirectoryDAO = mock[GoogleDirectoryDAO]
      val mockGooglePubSubDAO = new MockGooglePubSubDAO
      val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id)).thenReturn(Future.successful(Option(testGroup)))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id)).thenReturn(Future.successful(Option(testPolicy)))
      }
      when(mockDirectoryDAO.updateSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(()))
      when(mockDirectoryDAO.getSynchronizedDate(any[WorkbenchGroupIdentity])).thenReturn(Future.successful(Some(new Date(2017, 11, 22))))

      val subGroups = Seq(inSamSubGroup, inGoogleSubGroup, inBothSubGroup)
      subGroups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id)).thenReturn(Future.successful(Option(g.email))) }

      val added = Seq(inSamSubGroup.email, ge.toProxyFromUser(inSamUserId))
      val removed = Seq(inGoogleSubGroup.email, ge.toProxyFromUser(inGoogleUserId))

      when(mockGoogleDirectoryDAO.listGroupMembers(target.email)).thenReturn(Future.successful(Option(Seq(ge.toProxyFromUser(inGoogleUserId).value, ge.toProxyFromUser(inBothUserId).value, inGoogleSubGroup.email.value, inBothSubGroup.email.value, removeError))))
      when(mockGoogleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

      val addException = new Exception("addError")
      when(mockGoogleDirectoryDAO.addMemberToGroup(target.email, ge.toProxyFromUser(addError))).thenReturn(Future.failed(addException))

      val removeException = new Exception("removeError")
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(target.email, WorkbenchEmail(removeError))).thenReturn(Future.failed(removeException))

      val results = runAndWait(ge.synchronizeGroupMembers(target.id))

      results.head._1 should equal(target.email)
      results.head._2 should contain theSameElementsAs (
        added.map(e => SyncReportItem("added", e.value, None)) ++
          removed.map(e => SyncReportItem("removed", e.value, None)) ++
          Seq(
            SyncReportItem("added", ge.toProxyFromUser(addError).value, Option(ErrorReport(addException))),
            SyncReportItem("removed", removeError, Option(ErrorReport(removeException)))))

      added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(target.email, email) }
      removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(target.email, email) }
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
    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))
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
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig)

    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))
    val service = new UserService(dirDAO, googleExtensions)

    val defaultUserId = WorkbenchUserId("newuser")
    val defaultUserEmail = WorkbenchEmail("newuser@new.com")
    val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)

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
    val groupEmail = googleExtensions.toProxyFromUser(defaultUserId)
    mockGoogleIamDAO.serviceAccounts should contain key (petServiceAccount.serviceAccount.email)
    mockGoogleDirectoryDAO.groups should contain key (groupEmail)
    mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set(defaultUserEmail, petServiceAccount.serviceAccount.email)

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

  it should "get a group's last synchronized date" in {
    val groupName = WorkbenchGroupName("group1")

    val mockDirectoryDAO = mock[DirectoryDAO]

    val ge = new GoogleExtensions(mockDirectoryDAO, null, null, null, null, null, null, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(None))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe None

    val date = new Date()
    when(mockDirectoryDAO.getSynchronizedDate(groupName)).thenReturn(Future.successful(Some(date)))
    runAndWait(ge.getSynchronizedDate(groupName)) shouldBe Some(date)
  }

  it should "throw an exception with a NotFound error report when getting sync date for group that does not exist" in {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes(CloudExtensions.resourceTypeName))
    val groupName = WorkbenchGroupName("missing-group")
    val caught: WorkbenchExceptionWithErrorReport = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(ge.getSynchronizedDate(groupName))
    }
    caught.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    caught.errorReport.message should include (groupName.toString)
  }

  it should "return None when getting sync date for a group that has not been synced" in {
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val ge = new GoogleExtensions(dirDAO, null, null, null, null, null, null, googleServicesConfig, null, configResourceTypes(CloudExtensions.resourceTypeName))
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
    val ge = new GoogleExtensions(dirDAO, null, new MockGoogleDirectoryDAO(), null, null, null, null, googleServicesConfig, null, configResourceTypes(CloudExtensions.resourceTypeName))
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
    val googleKeyCache = new GoogleKeyCache(mockGoogleIamDAO, new MockGoogleStorageDAO, googleServicesConfig, petServiceAccountConfig)

    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, mockGooglePubSubDAO, mockGoogleIamDAO, mockGoogleStorageDAO, googleKeyCache, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val app = SamApplication(new UserService(mockDirectoryDAO, ge), new ResourceService(configResourceTypes, mockAccessPolicyDAO, mockDirectoryDAO, ge, "example.com"), null)
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), AccessPolicyName("owner"))

    runAndWait(mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId))) shouldBe None
    runAndWait(mockAccessPolicyDAO.loadPolicy(resourceAndPolicyName)) shouldBe None

    runAndWait(ge.onBoot(app))

    runAndWait(mockDirectoryDAO.loadUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId))) shouldBe Some(WorkbenchUser(WorkbenchUserId(googleServicesConfig.serviceAccountClientId), WorkbenchEmail(googleServicesConfig.serviceAccountClientEmail)))
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

  private def setupGoogleKeyCacheTests: (GoogleExtensions, UserService) = {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig)

    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.init())

    val mockGoogleIamDAO = new MockGoogleIamDAO
    val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO
    val mockGoogleStorageDAO = new MockGoogleStorageDAO
    val googleKeyCache = new GoogleKeyCache(mockGoogleIamDAO, mockGoogleStorageDAO, googleServicesConfig, petServiceAccountConfig)

    val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, mockGoogleStorageDAO, googleKeyCache, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))
    val service = new UserService(dirDAO, googleExtensions)

    (googleExtensions, service)
  }

  "GoogleKeyCache" should "create a service account key and return the same key when called again" in {
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
    runAndWait(googleExtensions.removePetServiceAccountKey(defaultUserId, googleProject, ServiceAccountKeyId(firstKey)))

    //get a key again, which should once again create a brand new one because we've deleted the cached one
    val secondKey = runAndWait(googleExtensions.getPetServiceAccountKey(defaultUser, googleProject))

    assert(firstKey != secondKey)
  }

}
