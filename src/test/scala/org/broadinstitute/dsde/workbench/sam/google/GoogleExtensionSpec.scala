package org.broadinstitute.dsde.workbench.sam.google

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, _}
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, JndiDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, UserService}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Try}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

class GoogleExtensionSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar with ScalaFutures {
  lazy val config = ConfigFactory.load()
  lazy val directoryConfig = config.as[DirectoryConfig]("directory")
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  "Google group sync" should "add/remove the right emails and handle errors" in {
    // tests that emails only in sam get added to google
    // emails only in google are removed
    // emails in both are neither added or removed
    // errors adding/removing to/from google are reported

    val groupName = WorkbenchGroupName("group1")
    val groupEmail = WorkbenchGroupEmail("group1@example.com")
    val inSamSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inSamSubGroup"), Set.empty, WorkbenchGroupEmail("inSamSubGroup@example.com"))
    val inGoogleSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inGoogleSubGroup"), Set.empty, WorkbenchGroupEmail("inGoogleSubGroup@example.com"))
    val inBothSubGroup = BasicWorkbenchGroup(WorkbenchGroupName("inBothSubGroup"), Set.empty, WorkbenchGroupEmail("inBothSubGroup@example.com"))

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
      val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDirectoryDAO, null, null, googleServicesConfig, petServiceAccountConfig)

      target match {
        case g: BasicWorkbenchGroup =>
          when(mockDirectoryDAO.loadGroup(g.id)).thenReturn(Future.successful(Option(testGroup)))
          when(mockDirectoryDAO.updateSynchronizedDate(g.id)).thenReturn(Future.successful(()))
        case p: AccessPolicy =>
          when(mockAccessPolicyDAO.loadPolicy(p.id)).thenReturn(Future.successful(Option(testPolicy)))
          when(mockDirectoryDAO.updateSynchronizedDate(p.id)).thenReturn(Future.successful(()))
      }

      val subGroups = Seq(inSamSubGroup, inGoogleSubGroup, inBothSubGroup)
      subGroups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id)).thenReturn(Future.successful(Option(g.email))) }

      val added = Seq(inSamSubGroup.email.value, ge.toProxyFromUser(inSamUserId.value))
      val removed = Seq(inGoogleSubGroup.email.value, ge.toProxyFromUser(inGoogleUserId.value))

      when(mockGoogleDirectoryDAO.listGroupMembers(target.email)).thenReturn(Future.successful(Option(Seq(ge.toProxyFromUser(inGoogleUserId.value), ge.toProxyFromUser(inBothUserId.value), inGoogleSubGroup.email.value, inBothSubGroup.email.value, removeError))))
      added.foreach { email => when(mockGoogleDirectoryDAO.addMemberToGroup(target.email, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }
      removed.foreach { email => when(mockGoogleDirectoryDAO.removeMemberFromGroup(target.email, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }

      val addException = new Exception("addError")
      when(mockGoogleDirectoryDAO.addMemberToGroup(target.email, WorkbenchUserEmail(ge.toProxyFromUser(addError.value)))).thenReturn(Future.failed(addException))

      val removeException = new Exception("removeError")
      when(mockGoogleDirectoryDAO.removeMemberFromGroup(target.email, WorkbenchUserEmail(removeError))).thenReturn(Future.failed(removeException))

      val results = runAndWait(ge.synchronizeGroupMembers(target.id))

      results.groupEmail should equal(target.email)
      results.items should contain theSameElementsAs (
        added.map(SyncReportItem("added", _, None)) ++
          removed.map(SyncReportItem("removed", _, None)) ++
          Seq(
            SyncReportItem("added", ge.toProxyFromUser(addError.value), Option(ErrorReport(addException))),
            SyncReportItem("removed", removeError, Option(ErrorReport(removeException)))))

      added.foreach { email => verify(mockGoogleDirectoryDAO).addMemberToGroup(target.email, WorkbenchUserEmail(email)) }
      removed.foreach { email => verify(mockGoogleDirectoryDAO).removeMemberFromGroup(target.email, WorkbenchUserEmail(email)) }
      verify(mockDirectoryDAO).updateSynchronizedDate(target.id)
    }
  }

  "GoogleExtension" should "get a pet service account for a user" in {
    implicit val patienceConfig = PatienceConfig(1 second)
    val dirDAO = new JndiDirectoryDAO(directoryConfig)
    val schemaDao = new JndiSchemaDAO(directoryConfig)
    runAndWait(schemaDao.init())
    try {
      val mockGoogleIamDAO = new MockGoogleIamDAO
      val mockGoogleDirectoryDAO = new MockGoogleDirectoryDAO

      val googleExtensions = new GoogleExtensions(dirDAO, null, mockGoogleDirectoryDAO, null, mockGoogleIamDAO, googleServicesConfig, petServiceAccountConfig)
      val service = new UserService(dirDAO, googleExtensions, mockGoogleDirectoryDAO, googleServicesConfig.appsDomain)

      val defaultUserId = WorkbenchUserId("newuser")
      val defaultUserEmail = WorkbenchUserEmail("newuser@new.com")
      val defaultUser = WorkbenchUser(defaultUserId, defaultUserEmail)

      // create a user
      val newUser = service.createUser(defaultUser).futureValue
      newUser shouldBe UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

      // create a pet service account
      val emailResponse = googleExtensions.createUserPetServiceAccount(defaultUser).futureValue

      emailResponse.value should endWith("@my-pet-project.iam.gserviceaccount.com")

      // verify ldap
      dirDAO.getPetServiceAccountForUser(defaultUserId).futureValue shouldBe Some(emailResponse)

      val ldapPetOpt = dirDAO.loadSubjectFromEmail(emailResponse.value).flatMap {
        case Some(subject: WorkbenchUserServiceAccountSubjectId) => dirDAO.loadPetServiceAccount(subject)
        case _ => fail(s"could not load pet LDAP entry from $emailResponse")
      }.futureValue

      ldapPetOpt shouldBe 'defined
      val Some(ldapPet) = ldapPetOpt
      ldapPet.email shouldBe WorkbenchUserServiceAccountEmail(emailResponse.value)
      ldapPet.displayName shouldBe WorkbenchUserServiceAccountDisplayName("")
      // MockGoogleIamDAO generates the subject ID as a random Long
      Try(ldapPet.subjectId.value.toLong) shouldBe a[Success[_]]

      // verify google
      val groupEmail = WorkbenchGroupEmail(googleExtensions.toProxyFromUser(defaultUserId.value))
      mockGoogleIamDAO.serviceAccounts should contain key (emailResponse)
      mockGoogleDirectoryDAO.groups should contain key (groupEmail)
      mockGoogleDirectoryDAO.groups(groupEmail) shouldBe Set(defaultUserEmail, emailResponse)

      // create one again, it should work
      val petSaResponse2 = googleExtensions.createUserPetServiceAccount(defaultUser).futureValue
      petSaResponse2 shouldBe emailResponse
    } finally {
      runAndWait(schemaDao.clearDatabase())
    }
  }
}
