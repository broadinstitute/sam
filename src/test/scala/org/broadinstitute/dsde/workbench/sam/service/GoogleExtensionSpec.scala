package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupEmail, WorkbenchGroupName, WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import org.mockito.Mockito
import org.mockito.Mockito._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class GoogleExtensionSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar {

  "Google group sync" should "add/remove the right emails" in {
    val mockAccessPolicyDAO = mock[AccessPolicyDAO]
    val mockDirectoryDAO = mock[DirectoryDAO]
    val mockGoogleDAO = mock[GoogleDirectoryDAO]
    val ge = new GoogleExtensions(mockDirectoryDAO, mockAccessPolicyDAO, mockGoogleDAO, "example.com")

    val groupName = WorkbenchGroupName("group1")
    val groupEmail = WorkbenchGroupEmail("group1@example.com")

    val inSamSubGroupName = WorkbenchGroupName("inSamSubGroup")
    val inSamSubGroupEmail = WorkbenchGroupEmail("inSamSubGroup@example.com")
    val inGoogleSubGroupName = WorkbenchGroupName("inGoogleSubGroup")
    val inGoogleSubGroupEmail = WorkbenchGroupEmail("inGoogleSubGroup@example.com")
    val inBothSubGroupName = WorkbenchGroupName("inBothSubGroup")
    val inBothSubGroupEmail = WorkbenchGroupEmail("inBothSubGroup@example.com")

    val inSamUserId = WorkbenchUserId("inSamUser")
    val inGoogleUserId = WorkbenchUserId("inGoogleUser")
    val inBothUserId = WorkbenchUserId("inBothUser")

    val groups = Seq(
      BasicWorkbenchGroup(inSamSubGroupName, Set.empty, inSamSubGroupEmail),
      BasicWorkbenchGroup(inGoogleSubGroupName, Set.empty, inGoogleSubGroupEmail),
      BasicWorkbenchGroup(inBothSubGroupName, Set.empty, inBothSubGroupEmail)
    )
    groups.foreach { g => when(mockDirectoryDAO.loadSubjectEmail(g.id)).thenReturn(Future.successful(Option(g.email))) }

    when(mockDirectoryDAO.loadGroup(groupName)).thenReturn(Future.successful(Option(BasicWorkbenchGroup(groupName, Set(inSamSubGroupName, inBothSubGroupName, inSamUserId, inBothUserId), groupEmail))))
    when(mockDirectoryDAO.updateSynchronizedDate(groupName)).thenReturn(Future.successful(()))

    val added = Seq(inSamSubGroupEmail.value, ge.toProxyFromUser(inSamUserId.value))
    val removed = Seq(inGoogleSubGroupEmail.value, ge.toProxyFromUser(inGoogleUserId.value))

    when(mockGoogleDAO.listGroupMembers(groupEmail)).thenReturn(Future.successful(Option(Seq(ge.toProxyFromUser(inGoogleUserId.value), ge.toProxyFromUser(inBothUserId.value), inGoogleSubGroupEmail.value, inBothSubGroupEmail.value))))
    added.foreach { email => when(mockGoogleDAO.addMemberToGroup(groupEmail, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }
    removed.foreach { email => when(mockGoogleDAO.removeMemberFromGroup(groupEmail, WorkbenchUserEmail(email))).thenReturn(Future.successful(())) }

    val results = runAndWait(ge.synchronizeGroupMembers(groupName))
    results.groupEmail should equal(groupEmail)
    results.items should contain theSameElementsAs(added.map(SyncReportItem("added", _, None)) ++ removed.map(SyncReportItem("removed", _, None)))

    added.foreach { email => verify(mockGoogleDAO).addMemberToGroup(groupEmail, WorkbenchUserEmail(email)) }
    removed.foreach { email => verify(mockGoogleDAO).removeMemberFromGroup(groupEmail, WorkbenchUserEmail(email)) }
    verify(mockDirectoryDAO).updateSynchronizedDate(groupName)
  }
}
