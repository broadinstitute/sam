package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.util.FutureSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class GoogleGroupSynchronizer(
    directoryDAO: DirectoryDAO,
    accessPolicyDAO: AccessPolicyDAO,
    googleDirectoryDAO: GoogleDirectoryDAO,
    googleExtensions: GoogleExtensions,
    resourceTypes: Map[ResourceTypeName, ResourceType])(implicit executionContext: ExecutionContext)
    extends LazyLogging
    with FutureSupport {
  def synchronizeGroupMembers(
      groupId: WorkbenchGroupIdentity,
      visitedGroups: Set[WorkbenchGroupIdentity] = Set.empty[WorkbenchGroupIdentity]): Future[Map[WorkbenchEmail, Seq[SyncReportItem]]] = {
    def toSyncReportItem(operation: String, email: String, result: Try[Unit]) =
      SyncReportItem(
        operation,
        email,
        result match {
          case Success(_) => None
          case Failure(t) => Option(ErrorReport(t))
        }
      )

    if (visitedGroups.contains(groupId)) {
      Future.successful(Map.empty)
    } else {
      for {
        groupOption <- groupId match {
          case basicGroupName: WorkbenchGroupName => directoryDAO.loadGroup(basicGroupName).unsafeToFuture()
          case rpn: FullyQualifiedPolicyId =>
            accessPolicyDAO
              .loadPolicy(rpn)
              .unsafeToFuture()
              .map(_.map { loadedPolicy =>
                if (loadedPolicy.public) {
                  // include all users group when synchronizing a public policy
                  AccessPolicy.members.modify(_ + googleExtensions.allUsersGroupName)(loadedPolicy)
                } else {
                  loadedPolicy
                }
              })
        }

        group = groupOption.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))

        members <- (group match {
          case accessPolicy: AccessPolicy =>
            if (isConstrainable(accessPolicy.id.resource, accessPolicy)) {
              calculateIntersectionGroup(accessPolicy.id.resource, accessPolicy)
            } else {
              IO.pure(accessPolicy.members)
            }
          case group: BasicWorkbenchGroup => IO.pure(group.members)
        }).unsafeToFuture()

        subGroupSyncs <- Future.traverse(group.members) {
          case subGroup: WorkbenchGroupIdentity =>
            directoryDAO.getSynchronizedDate(subGroup).unsafeToFuture().flatMap {
              case None => synchronizeGroupMembers(subGroup, visitedGroups + groupId)
              case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
            }
          case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
        }

        googleMemberEmails <- googleDirectoryDAO.listGroupMembers(group.email) flatMap {
          case None =>
            googleDirectoryDAO.createGroup(groupId.toString, group.email, Option(googleDirectoryDAO.lockedDownGroupSettings)) map (_ => Set.empty[String])
          case Some(members) => Future.successful(members.map(_.toLowerCase).toSet)
        }
        samMemberEmails <- Future
          .traverse(members) {
            case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group).unsafeToFuture()

            // use proxy group email instead of user's actual email
            case userSubjectId: WorkbenchUserId => googleExtensions.getUserProxy(userSubjectId)

            // not sure why this next case would happen but if a petSA is in a group just use its email
            case petSA: PetServiceAccountId => directoryDAO.loadSubjectEmail(petSA).unsafeToFuture()
          }
          .map(_.collect { case Some(email) => email.value.toLowerCase })

        toAdd = samMemberEmails -- googleMemberEmails
        toRemove = googleMemberEmails -- samMemberEmails

        addTrials <- Future.traverse(toAdd) { addEmail =>
          googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _))
        }
        removeTrials <- Future.traverse(toRemove) { removeEmail =>
          googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _))
        }

        _ <- directoryDAO.updateSynchronizedDate(groupId)
      } yield {
        Map(group.email -> Seq(addTrials, removeTrials).flatten) ++ subGroupSyncs.flatten
      }
    }
  }

  private[google] def isConstrainable(resource: FullyQualifiedResourceId, accessPolicy: AccessPolicy): Boolean =
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) =>
        resourceType.actionPatterns.exists { actionPattern =>
          actionPattern.authDomainConstrainable &&
          (accessPolicy.actions.exists(actionPattern.matches) ||
          accessPolicy.roles.exists { accessPolicyRole =>
            resourceType.roles.exists {
              case resourceTypeRole @ ResourceRole(`accessPolicyRole`, _) => resourceTypeRole.actions.exists(actionPattern.matches)
              case _ => false
            }
          })
        }
      case None =>
        throw new WorkbenchException(s"Invalid resource type specified. ${resource.resourceTypeName} is not a recognized resource type.")
    }

  private[google] def calculateIntersectionGroup(resource: FullyQualifiedResourceId, policy: AccessPolicy): IO[Set[WorkbenchUserId]] =
    for {
      groups <- accessPolicyDAO.loadResourceAuthDomain(resource)
      members <- directoryDAO.listIntersectionGroupUsers(groups.asInstanceOf[Set[WorkbenchGroupIdentity]] + policy.id)
    } yield {
      members
    }

}
