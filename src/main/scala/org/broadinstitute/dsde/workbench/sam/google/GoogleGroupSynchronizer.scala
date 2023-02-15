package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.FutureSupport

/** This class makes sure that our google groups have the right members.
  *
  * For the simple case it merely compares group membership given by directoryDAO against group membership given by googleDirectoryDAO and does the appropriate
  * adds and removes to google so that they look the same.
  *
  * The more complicated case involves resources contsrained by an auth domain. If a resource is constrained by an auth domain AND the policy being synchronized
  * has actions or roles configured as contstrainable then we need to synchronize the *intersection* of the members of all the groups in the auth domain and the
  * access policy. These are called intersection groups. In order to do this accurately all the groups must be unrolled (flattened).
  *
  * @param directoryDAO
  * @param accessPolicyDAO
  * @param googleDirectoryDAO
  * @param googleExtensions
  * @param resourceTypes
  */
class GoogleGroupSynchronizer(
    directoryDAO: DirectoryDAO,
    accessPolicyDAO: AccessPolicyDAO,
    googleDirectoryDAO: GoogleDirectoryDAO,
    googleExtensions: GoogleExtensions,
    resourceTypes: Map[ResourceTypeName, ResourceType]
) extends LazyLogging
    with FutureSupport {

  def init(): IO[Set[ResourceTypeName]] =
    accessPolicyDAO.upsertResourceTypes(resourceTypes.values.toSet, SamRequestContext())

  def synchronizeGroupMembers(
      groupId: WorkbenchGroupIdentity,
      visitedGroups: Set[WorkbenchGroupIdentity] = Set.empty[WorkbenchGroupIdentity],
      samRequestContext: SamRequestContext
  ): IO[Map[WorkbenchEmail, Seq[SyncReportItem]]] =
    if (visitedGroups.contains(groupId)) {
      IO.pure(Map.empty)
    } else {
      for {
        group <- loadSamGroup(groupId, samRequestContext)
        members <- calculateAuthDomainIntersectionIfRequired(group, samRequestContext)
        subGroupSyncs <- syncSubGroupsIfRequired(group, visitedGroups, samRequestContext)
        googleMemberEmails <- loadGoogleGroupMemberEmailsMaybeCreateGroup(group, samRequestContext)
        samMemberEmails <- loadSamMemberEmails(members, samRequestContext)

        toAdd = samMemberEmails -- googleMemberEmails
        toRemove = googleMemberEmails -- samMemberEmails

        addedUserSyncReports <- toAdd.toList.traverse(addMemberToGoogleGroup(group, samRequestContext))
        removedUserSyncReports <- toRemove.toList.traverse(removeMemberFromGoogleGroup(group, samRequestContext))

        _ <- directoryDAO.updateSynchronizedDate(groupId, samRequestContext)
      } yield Map(group.email -> Seq(addedUserSyncReports, removedUserSyncReports).flatten) ++ subGroupSyncs.flatten
    }

  private def removeMemberFromGoogleGroup(group: WorkbenchGroup, samRequestContext: SamRequestContext)(removeEmail: String) =
    traceIOWithContext("removeMemberFromGoogleGroup", samRequestContext) { _ =>
      SyncReportItem.fromIO("removed", removeEmail, IO.fromFuture(IO(googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchEmail(removeEmail)))))
    }

  private def addMemberToGoogleGroup(group: WorkbenchGroup, samRequestContext: SamRequestContext)(addEmail: String) =
    traceIOWithContext("addMemberToGoogleGroup", samRequestContext) { _ =>
      SyncReportItem.fromIO("added", addEmail, IO.fromFuture(IO(googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchEmail(addEmail)))))
    }

  /** convert each subject to an email address
    *
    * @param members
    * @param samRequestContext
    * @return
    */
  private def loadSamMemberEmails(members: Set[WorkbenchSubject], samRequestContext: SamRequestContext) =
    members.toList
      .traverse {
        case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group, samRequestContext)

        // use proxy group email instead of user's actual email
        case userSubjectId: WorkbenchUserId => googleExtensions.getUserProxy(userSubjectId)

        // not sure why this next case would happen but if a petSA is in a group just use its email
        case petSA: PetServiceAccountId => directoryDAO.loadSubjectEmail(petSA, samRequestContext)
      }
      .map(_.collect { case Some(email) => email.value.toLowerCase }.toSet)

  /** If the group exists in google, return its members, otherwise create it and return empty
    * @param group
    * @return
    */
  private def loadGoogleGroupMemberEmailsMaybeCreateGroup(group: WorkbenchGroup, samRequestContext: SamRequestContext) = {
    // note that these 2 definitions are IOs so they don't actually do anything until they are used below
    val listMembersIO = traceIOWithContext("listGoogleGroupMembers", samRequestContext) { _ =>
      IO.fromFuture(IO(googleDirectoryDAO.listGroupMembers(group.email)))
    }
    val createGroupIO = traceIOWithContext("createGoogleGroup", samRequestContext) { _ =>
      IO.fromFuture(IO(googleDirectoryDAO.createGroup(group.id.toString, group.email, Option(googleDirectoryDAO.lockedDownGroupSettings))))
    }

    listMembersIO flatMap {
      case None => createGroupIO map (_ => Set.empty[String])
      case Some(members) => IO.pure(members.map(_.toLowerCase).toSet)
    }
  }

  /** Synchronize any sub groups that have never been synchronized otherwise they don't exist in google and can't be added. This is effectively a recursive call
    * to synchronizeGroupMembers so it may fan out even further.
    * @param group
    * @param visitedGroups
    *   used to break out of any group hierarchy cycles, there shouldn't be any but nobody likes infinite loops
    * @param samRequestContext
    * @return
    */
  private def syncSubGroupsIfRequired(group: WorkbenchGroup, visitedGroups: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext) =
    group.members.toList.traverse {
      case subGroup: WorkbenchGroupIdentity =>
        directoryDAO.getSynchronizedDate(subGroup, samRequestContext).flatMap {
          case None => synchronizeGroupMembers(subGroup, visitedGroups + group.id, samRequestContext)
          case _ => IO.pure(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
        }
      case _ => IO.pure(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
    }

  /** If this is a policy group constrained by an auth domain calculate the intersection group membership, otherwise just return the group membership
    * @param group
    * @param samRequestContext
    * @return
    */
  private def calculateAuthDomainIntersectionIfRequired(group: WorkbenchGroup, samRequestContext: SamRequestContext): IO[Set[WorkbenchSubject]] =
    group match {
      case accessPolicy: AccessPolicy =>
        if (isConstrainable(accessPolicy.id.resource, accessPolicy)) {
          calculateIntersectionGroup(accessPolicy.id.resource, accessPolicy, samRequestContext)
        } else {
          IO.pure(accessPolicy.members)
        }
      case group: BasicWorkbenchGroup => IO.pure(group.members)
    }

  /** Loads the group whether a policy or basic group. If it is a public policy add the all users group to members.
    * @param groupId
    * @param samRequestContext
    * @return
    */
  private def loadSamGroup(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[WorkbenchGroup] =
    for {
      groupOption <- groupId match {
        case basicGroupName: WorkbenchGroupName => directoryDAO.loadGroup(basicGroupName, samRequestContext)
        case rpn: FullyQualifiedPolicyId =>
          accessPolicyDAO
            .loadPolicy(rpn, samRequestContext)
            .map(_.map { loadedPolicy =>
              if (loadedPolicy.public) {
                // include all users group when synchronizing a public policy
                AccessPolicy.members.modify(_ + CloudExtensions.allUsersGroupName)(loadedPolicy)
              } else {
                loadedPolicy
              }
            })
      }

      group <- OptionT.fromOption[IO](groupOption).getOrRaise(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
    } yield group

  /** An access policy is constrainable if it contains an action or a role that contains an action that is configured as constrainable in the resource type
    * definition.
    *
    * @param resource
    * @param accessPolicy
    * @return
    */
  private[google] def isConstrainable(resource: FullyQualifiedResourceId, accessPolicy: AccessPolicy): Boolean =
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) =>
        resourceType.actionPatterns.exists { actionPattern =>
          actionPattern.authDomainConstrainable &&
          (accessPolicy.actions.exists(actionPattern.matches) ||
            accessPolicy.roles.exists { accessPolicyRole =>
              resourceType.roles.exists {
                case resourceTypeRole @ ResourceRole(`accessPolicyRole`, _, _, _) => resourceTypeRole.actions.exists(actionPattern.matches)
                case _ => false
              }
            })
        }
      case None =>
        throw new WorkbenchException(s"Invalid resource type specified. ${resource.resourceTypeName} is not a recognized resource type.")
    }

  private[google] def calculateIntersectionGroup(
      resource: FullyQualifiedResourceId,
      policy: AccessPolicy,
      samRequestContext: SamRequestContext
  ): IO[Set[WorkbenchSubject]] =
    // if the policy has no members, the intersection will be empty so short circuit here
    if (policy.members.isEmpty) {
      IO.pure(Set())
    } else {
      for {
        result <- accessPolicyDAO.loadResourceAuthDomain(resource, samRequestContext)
        members <- result match {
          case LoadResourceAuthDomainResult.Constrained(groups) =>
            // auth domain exists, need to calculate intersection
            val groupsIdentity: Set[WorkbenchGroupIdentity] = groups.toList.toSet
            directoryDAO
              .listIntersectionGroupUsers(groupsIdentity + policy.id, samRequestContext)
              .map(_.map(_.asInstanceOf[WorkbenchSubject])) // Doesn't seem like I can avoid the asInstanceOf, would be interested to know if there's a way
          case LoadResourceAuthDomainResult.NotConstrained | LoadResourceAuthDomainResult.ResourceNotFound =>
            // auth domain does not exist, return policy members as is
            IO.pure(policy.members)
        }
      } yield members
    }
}
