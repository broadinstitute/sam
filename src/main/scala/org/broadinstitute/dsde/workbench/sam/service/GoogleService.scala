package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.ResourceAndPolicyName
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.util.FutureSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class GoogleService(val directoryDAO: DirectoryDAO, val accessPolicyDAO: AccessPolicyDAO, val gcsDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging with FutureSupport {
  private[service] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"

  case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])
  case class SyncReport(groupEmail: WorkbenchGroupEmail, items: Seq[SyncReportItem])

  def synchronizeGroupMembers(groupId: WorkbenchGroupIdentity): Future[SyncReport] = {
    def toSyncReportItem(operation: String, email: String, result: Try[Unit]) = {
      SyncReportItem(
        operation,
        email,
        result match {
          case Success(_) => None
          case Failure(t) => Option(ErrorReport(t))
        }
      )
    }

    for {
      groupOption <- groupId match {
        case basicGroupName: WorkbenchGroupName => directoryDAO.loadGroup(basicGroupName)
        case rpn: ResourceAndPolicyName => accessPolicyDAO.loadPolicy(rpn)
      }

      group = groupOption.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))

      googleMemberEmails <- gcsDAO.listGroupMembers(group.email) flatMap {
        case None => gcsDAO.createGroup(groupId.toString, group.email) map (_ => Set.empty[String])
        case Some(members) => Future.successful(members.toSet)
      }

      samMemberEmails <- Future.traverse(group.members) {
        case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group)
        case WorkbenchUserId(userSubjectId) => Future.successful(Option(WorkbenchUserEmail(toProxyFromUser(userSubjectId))))
      }.map(_.collect { case Some(email) => email.value })

      toAdd = samMemberEmails -- googleMemberEmails
      toRemove = googleMemberEmails -- samMemberEmails

      addTrials <- Future.traverse(toAdd) { addEmail => gcsDAO.addMemberToGroup(group.email, WorkbenchUserEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _)) }
      removeTrials <- Future.traverse(toRemove) { removeEmail => gcsDAO.removeMemberFromGroup(group.email, WorkbenchUserEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _)) }

      _ <- directoryDAO.updateSynchronizedDate(groupId)
    } yield {
      SyncReport(group.email, Seq(addTrials, removeTrials).flatten)
    }
  }
}
