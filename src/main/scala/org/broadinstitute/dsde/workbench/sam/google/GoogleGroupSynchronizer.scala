package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
//import com.google.api.services.admin.directory.Directory
//import com.google.api.services.admin.directory.model.Group
//import com.google.api.services.groupssettings.{Groupssettings, GroupssettingsScopes}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.util.FutureSupport

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import io.opencensus.scala.Tracing._
//import io.opencensus.trace.Span
//import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.{httpTransport, jsonFactory}
//import org.broadinstitute.dsde.workbench.google.GoogleUtilities.RetryPredicates.{when404, when5xx, whenInvalidValueOnBucketCreation, whenNonHttpIOException, whenUsageLimited}
//import org.broadinstitute.dsde.workbench.metrics.GoogleInstrumentedService

/**
  * This class makes sure that our google groups have the right members.
  *
  * For the simple case it merely compares
  * group membership given by directoryDAO against group membership given by googleDirectoryDAO and does the
  * appropriate adds and removes to google so that they look the same.
  *
  * The more complicated case involves resources contsrained by an auth domain. If a resource is constrained by an
  * auth domain AND the policy being synchronized has actions or roles configured as contstrainable then we need to
  * synchronize the *intersection* of the members of all the groups in the auth domain and the access policy. These
  * are called intersection groups. In order to do this accurately all the groups must be unrolled (flattened).
  *
  * @param directoryDAO
  * @param accessPolicyDAO
  * @param googleDirectoryDAO
  * @param googleExtensions
  * @param resourceTypes
  * @param executionContext
  */
class GoogleGroupSynchronizer(directoryDAO: DirectoryDAO,
                              accessPolicyDAO: AccessPolicyDAO,
                              googleDirectoryDAO: GoogleDirectoryDAO,
                              googleExtensions: GoogleExtensions,
                              resourceTypes: Map[ResourceTypeName, ResourceType])(implicit executionContext: ExecutionContext)
  extends LazyLogging with FutureSupport {
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

    trace("synchronizeGroupMembers")( span => if (visitedGroups.contains(groupId)) {
      Future.successful(Map.empty)
    } else {
      for {
        groupOption <- groupId match {
          case basicGroupName: WorkbenchGroupName => traceWithParent("loadGroup",span)( _ => directoryDAO.loadGroup(basicGroupName).unsafeToFuture())
          case rpn: FullyQualifiedPolicyId =>
            traceWithParent("loadPolicy",span)( _ => accessPolicyDAO
              .loadPolicy(rpn)
              .unsafeToFuture()
              .map(_.map { loadedPolicy =>
                if (loadedPolicy.public) {
                  // include all users group when synchronizing a public policy
                  AccessPolicy.members.modify(_ + googleExtensions.allUsersGroupName)(loadedPolicy)
                } else {
                  loadedPolicy
                }
              }))
        }

        group = groupOption.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))

        members <- traceWithParent("calculateMembers",span)( _ => (group match {
          case accessPolicy: AccessPolicy =>
            if (isConstrainable(accessPolicy.id.resource, accessPolicy)) {
              calculateIntersectionGroup(accessPolicy.id.resource, accessPolicy)
            } else {
              IO.pure(accessPolicy.members)
            }
          case group: BasicWorkbenchGroup => IO.pure(group.members)
        }).unsafeToFuture())

        subGroupSyncs <- traceWithParent("syncSubgroups",span)( _ => Future.traverse(group.members) {
          case subGroup: WorkbenchGroupIdentity =>
            directoryDAO.getSynchronizedDate(subGroup).unsafeToFuture().flatMap {
              case None => synchronizeGroupMembers(subGroup, visitedGroups + groupId)
              case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
            }
          case _ => Future.successful(Map.empty[WorkbenchEmail, Seq[SyncReportItem]])
        })

        googleMemberEmails <- traceWithParent("listGroupMembers", span)(_ => googleDirectoryDAO.listGroupMembers(group.email)) flatMap {
          case None =>
            traceWithParent("createGroup",span)( s2 => googleDirectoryDAO.createGroup(groupId.toString, group.email, Option(googleDirectoryDAO.lockedDownGroupSettings))(s2)) map (_ => Set.empty[String])
          case Some(members) => Future.successful(members.map(_.toLowerCase).toSet)
        }

        samMemberEmails <- traceWithParent("gatherEmails",span)( _ => Future
          .traverse(members) {
            case group: WorkbenchGroupIdentity => directoryDAO.loadSubjectEmail(group).unsafeToFuture()

            // use proxy group email instead of user's actual email
            case userSubjectId: WorkbenchUserId => googleExtensions.getUserProxy(userSubjectId)

            // not sure why this next case would happen but if a petSA is in a group just use its email
            case petSA: PetServiceAccountId => directoryDAO.loadSubjectEmail(petSA).unsafeToFuture()
          }
          .map(_.collect { case Some(email) => email.value.toLowerCase }))

        toAdd = samMemberEmails -- googleMemberEmails
        toRemove = googleMemberEmails -- samMemberEmails

        addTrials <- traceWithParent("addMembers",span)( _ => Future.traverse(toAdd) { addEmail =>
          googleDirectoryDAO.addMemberToGroup(group.email, WorkbenchEmail(addEmail)).toTry.map(toSyncReportItem("added", addEmail, _))
        })
        removeTrials <- traceWithParent("removeMembers",span)( _ => Future.traverse(toRemove) { removeEmail =>
          googleDirectoryDAO.removeMemberFromGroup(group.email, WorkbenchEmail(removeEmail)).toTry.map(toSyncReportItem("removed", removeEmail, _))
        })

        _ <- traceWithParent("updateSyncDate",span)( _ => directoryDAO.updateSynchronizedDate(groupId).unsafeToFuture())
      } yield {
        Map(group.email -> Seq(addTrials, removeTrials).flatten) ++ subGroupSyncs.flatten
      }
    })

  }

//
//  val googleCredential = null  // FIXME!!
//
//  private lazy val directory = {
//    new Directory.Builder(httpTransport, jsonFactory, googleCredential).setApplicationName("local-sam").build()
//  }
//
//  import com.google.api.services.groupssettings.model.{Groups => GroupSettings}
//  val appName = "sam"
//
//  private class GroupSettingsDAO() extends AbstractHttpGoogleDAO(appName, googleCredentialMode, workbenchMetricBaseName) {
//    override implicit val service = GoogleInstrumentedService.Groups
//    override val scopes = Seq(GroupssettingsScopes.APPS_GROUPS_SETTINGS)
//    private lazy val settingsClient = new Groupssettings.Builder(httpTransport, jsonFactory, googleCredential).setApplicationName(appName).build()
//
//
//    def updateGroupSettings(groupEmail: WorkbenchEmail, settings: GroupSettings) = {
//      val updater = settingsClient.groups().update(groupEmail.value, settings)
//      retry(when5xx, whenUsageLimited, when404, whenInvalidValueOnBucketCreation, whenNonHttpIOException) (() => { executeGoogleRequest(updater) })
//    }
//  }
//
//  def localCreateGroup(displayName: String, groupEmail: WorkbenchEmail, groupSettings: Option[GroupSettings] = None)(implicit span: Span = null): Future[Unit] = {
//
//    val groups = directory.groups
//    val group = new Group().setEmail(groupEmail.value).setName(displayName.take(60)) //max google group name length is 60 characters
//    val inserter = groups.insert(group)
//
//    for {
//      _ <- retry(when5xx, whenUsageLimited, when404, whenInvalidValueOnBucketCreation, whenNonHttpIOException)(() => { executeGoogleRequest(inserter) })
//      _ <- groupSettings match {
//        case None => Future.successful(())
//        case Some(settings) => traceWithParent("update", span)(_ => new GroupSettingsDAO().updateGroupSettings(groupEmail, settings))
//      }
//    } yield ()
//  }
  /**
    * An access policy is constrainable if it contains an action or a role that contains an action that is
    * configured as constrainable in the resource type definition.
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
                  case resourceTypeRole @ ResourceRole(`accessPolicyRole`, _) => resourceTypeRole.actions.exists(actionPattern.matches)
                  case _ => false
                }
              })
        }
      case None =>
        throw new WorkbenchException(s"Invalid resource type specified. ${resource.resourceTypeName} is not a recognized resource type.")
    }

  private[google] def calculateIntersectionGroup(resource: FullyQualifiedResourceId, policy: AccessPolicy): IO[Set[WorkbenchSubject]] = {
    // if the policy has no members, the intersection will be empty so short circuit here
    if (policy.members.isEmpty) {
      IO.pure(Set())
    } else {
      for {
        result <- accessPolicyDAO.loadResourceAuthDomain(resource)
        members <- result match {
          case LoadResourceAuthDomainResult.Constrained(groups) =>
            // auth domain exists, need to calculate intersection
            val groupsIdentity: Set[WorkbenchGroupIdentity] = groups.toList.toSet
            directoryDAO.listIntersectionGroupUsers(groupsIdentity + policy.id).map(_.map(_.asInstanceOf[WorkbenchSubject])) //Doesn't seem like I can avoid the asInstanceOf, would be interested to know if there's a way
          case LoadResourceAuthDomainResult.NotConstrained | LoadResourceAuthDomainResult.ResourceNotFound =>
            // auth domain does not exist, return policy members as is
            IO.pure(policy.members)
        }
      } yield members
    }
  }
}