package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupName, WorkbenchUserId}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class GoogleService {
  def synchronizeGroupMembersInternal(group: WorkbenchGroupI): Future[SyncReport] = {
    def loadRefs(refs: Set[Either[WorkbenchUserId, WorkbenchGroupName]]) = {
      DBIO.sequence(refs.map {
        case Left(userRef) => dataAccess.rawlsUserQuery.load(userRef).map(userOption => Left(userOption.getOrElse(throw new RawlsException(s"user $userRef not found"))))
        case Right(groupRef) => dataAccess.rawlsGroupQuery.load(groupRef).map(groupOption => Right(groupOption.getOrElse(throw new RawlsException(s"group $groupRef not found"))))
      }.toSeq)
    }

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

    DBIO.from(gcsDAO.listGroupMembers(group)) flatMap {
      case None => DBIO.from(gcsDAO.createGoogleGroup(group) map (_ => Map.empty[String, Option[Either[WorkbenchUserId, WorkbenchGroupName]]]))
      case Some(members) => DBIO.successful(members)
    } flatMap { membersByEmail =>

      val knownEmailsByMember = membersByEmail.collect { case (email, Some(member)) => (member, email) }
      val unknownEmails = membersByEmail.collect { case (email, None) => email }

      val toRemove = knownEmailsByMember.keySet -- group.users.map(Left(_)) -- group.subGroups.map(Right(_))
      val emailsToRemove = unknownEmails ++ toRemove.map(knownEmailsByMember)
      val removeFutures = DBIO.sequence(emailsToRemove map { removeMember =>
        DBIO.from(toFutureTry(gcsDAO.removeEmailFromGoogleGroup(group.groupEmail.value, removeMember)).map(toSyncReportItem("removed", removeMember, _)))
      })


      val realMembers: Set[Either[WorkbenchUserId, WorkbenchGroupName]] = group.users.map(Left(_)) ++ group.subGroups.map(Right(_))
      val toAdd = realMembers -- knownEmailsByMember.keySet
      val addFutures = loadRefs(toAdd) flatMap { addMembers =>
        DBIO.sequence(addMembers map { addMember =>
          val memberEmail = addMember match {
            case Left(user) => user.userEmail.value
            case Right(subGroup) => subGroup.groupEmail.value
          }
          DBIO.from(toFutureTry(gcsDAO.addMemberToGoogleGroup(group, addMember)).map(toSyncReportItem("added", memberEmail, _)))
        })
      }

      for {
        syncReportItems <- DBIO.sequence(Seq(removeFutures, addFutures))
        _ <- dataAccess.rawlsGroupQuery.updateSynchronizedDate(group)
      } yield {
        SyncReport(group.groupEmail, syncReportItems.flatten)
      }
    }
  }
}
