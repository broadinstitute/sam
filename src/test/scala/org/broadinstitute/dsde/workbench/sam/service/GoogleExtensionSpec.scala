package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import org.mockito.Mockito
import org.mockito.Mockito._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class GoogleExtensionSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar {

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
      val mockGoogleDAO = mock[GoogleDirectoryDAO]
      val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDAO, "example.com")

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

      when(mockGoogleDAO.listGroupMembers(target.email)).thenReturn(Future.successful(Option(Seq(ge.toProxyFromUser(inGoogleUserId.value), ge.toProxyFromUser(inBothUserId.value), inGoogleSubGroup.email.value, inBothSubGroup.email.value, removeError))))
      added.foreach { email => when(mockGoogleDAO.addMemberToGroup(target.email, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }
      removed.foreach { email => when(mockGoogleDAO.removeMemberFromGroup(target.email, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }

      val addException = new Exception("addError")
      when(mockGoogleDAO.addMemberToGroup(target.email, WorkbenchUserEmail(ge.toProxyFromUser(addError.value)))).thenReturn(Future.failed(addException))

      val removeException = new Exception("removeError")
      when(mockGoogleDAO.removeMemberFromGroup(target.email, WorkbenchUserEmail(removeError))).thenReturn(Future.failed(removeException))

      val results = runAndWait(ge.synchronizeGroupMembers(target.id))

      results.groupEmail should equal(target.email)
      results.items should contain theSameElementsAs (
        added.map(SyncReportItem("added", _, None)) ++
          removed.map(SyncReportItem("removed", _, None)) ++
          Seq(
            SyncReportItem("added", ge.toProxyFromUser(addError.value), Option(ErrorReport(addException))),
            SyncReportItem("removed", removeError, Option(ErrorReport(removeException)))))

      added.foreach { email => verify(mockGoogleDAO).addMemberToGroup(target.email, WorkbenchUserEmail(email)) }
      removed.foreach { email => verify(mockGoogleDAO).removeMemberFromGroup(target.email, WorkbenchUserEmail(email)) }
      verify(mockDirectoryDAO).updateSynchronizedDate(target.id)
    }
  }
}
