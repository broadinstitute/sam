package org.broadinstitute.dsde.workbench.sam.google

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

class GoogleExtensions(val directoryDAO: DirectoryDAO, val accessPolicyDAO: AccessPolicyDAO, val googleDirectoryDAO: GoogleDirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging with FutureSupport {
  private[google] def toProxyFromUser(subjectId: String): String = s"PROXY_$subjectId@$googleDomain"

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

      googleMemberEmails <- googleDirectoryDAO.listGroupMembers(group.email) flatMap {
        case None => googleDirectoryDAO.createGroup(groupId.toString, group.email) map (_ => Set.empty[String])
        case Some(members) => Future.successful(members.toSet)
      }

      samMemberEmails <- Future.traverse(group.members) {
        case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group)

        // use proxy group email instead of user's actual email
        case WorkbenchUserId(userSubjectId) => Future.successful(Option(WorkbenchUserEmail(toProxyFromUser(userSubjectId))))

        // not sure why this next case would happen but if a petSA is in a group just use its email
        case petSA: WorkbenchUserServiceAccountSubjectId => directoryDAO.loadSubjectEmail(petSA)
      }.map(_.collect { case Some(email) => email.value })

      toAdd = samMemberEmails -- googleMemberEmails
      toRemove = googleMemberEmails -- samMemberEmails

      addTrials <- Future.traverse(toAdd) { addEmail => googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchUserEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _)) }
      removeTrials <- Future.traverse(toRemove) { removeEmail => googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchUserEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _)) }

      _ <- directoryDAO.updateSynchronizedDate(groupId)
    } yield {
      SyncReport(group.email, Seq(addTrials, removeTrials).flatten)
    }
  }
}
