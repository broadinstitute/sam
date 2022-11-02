package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{ResourceId, _}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.ManagedGroupPolicyName
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

/** Created by gpolumbo on 2/20/2018.
  */
trait ManagedGroupRoutes extends SamUserDirectives with SecurityDirectives with SamModelDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext

  val managedGroupService: ManagedGroupService

  def groupRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    (pathPrefix("groups" / "v1") | pathPrefix("group")) {
      pathPrefix(Segment) { groupId =>
        val managedGroup = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))

        pathEndOrSingleSlash {
          get {
            handleGetGroup(managedGroup.resourceId, samRequestContext)
          } ~ post {
            handleCreateGroup(managedGroup.resourceId, samUser, samRequestContext)
          } ~ delete {
            handleDeleteGroup(managedGroup, samUser, samRequestContext)
          }
        } ~ pathPrefix("requestAccess") {
          post {
            handleRequestAccess(managedGroup, samUser, samRequestContext)
          }
        } ~ path("accessInstructions") {
          put {
            entity(as[ManagedGroupAccessInstructions]) { accessInstructions =>
              handleSetAccessInstructions(managedGroup, accessInstructions, samUser, samRequestContext)
            }
          } ~ get {
            handleGetAccessInstructions(managedGroup, samRequestContext)
          }
        } ~ pathPrefix(Segment) { policyName =>
          val accessPolicyName = ManagedGroupService.getPolicyName(policyName)

          pathEndOrSingleSlash {
            get {
              handleListEmails(managedGroup, accessPolicyName, samUser, samRequestContext)
            } ~ put {
              handleOverwriteEmails(managedGroup, accessPolicyName, samUser, samRequestContext)
            }
          } ~ pathPrefix(Segment) { email =>
            pathEndOrSingleSlash {
              put {
                handleAddEmailToPolicy(managedGroup, accessPolicyName, email, samUser, samRequestContext)
              } ~ delete {
                handleDeleteEmailFromPolicy(managedGroup, accessPolicyName, email, samUser, samRequestContext)
              }
            }
          }
        }
      }
    } ~ (pathPrefix("groups" / "v1") | pathPrefix("groups")) {
      pathEndOrSingleSlash {
        get {
          handleListGroups(samUser, samRequestContext)
        }
      }
    }

  private def handleListGroups(samUser: SamUser, samRequestContext: SamRequestContext) =
    complete(managedGroupService.listGroups(samUser.id, samRequestContext).map(StatusCodes.OK -> _))

  private def handleGetGroup(resourceId: ResourceId, samRequestContext: SamRequestContext): Route =
    complete(managedGroupService.loadManagedGroup(resourceId, samRequestContext).flatMap {
      case Some(response) => IO.pure(StatusCodes.OK -> response)
      case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))
    })

  private def handleCreateGroup(resourceId: ResourceId, samUser: SamUser, samRequestContext: SamRequestContext): Route =
    complete(managedGroupService.createManagedGroup(resourceId, samUser, samRequestContext = samRequestContext).map(_ => StatusCodes.Created))

  private def handleDeleteGroup(managedGroup: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): Route =
    requireAction(managedGroup, SamResourceActions.delete, samUser.id, samRequestContext) {
      complete(managedGroupService.deleteManagedGroup(managedGroup.resourceId, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  private def handleListEmails(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.readPolicy(accessPolicyName), samUser.id, samRequestContext) {
      complete(managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, accessPolicyName, samRequestContext).map(StatusCodes.OK -> _))
    }

  private def handleOverwriteEmails(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), samUser.id, samRequestContext) {
      entity(as[Set[WorkbenchEmail]]) { members =>
        complete(
          managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, accessPolicyName, members, samRequestContext).map(_ => StatusCodes.Created)
        )
      }
    }

  private def handleAddEmailToPolicy(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      email: String,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), samUser.id, samRequestContext) {
      withSubject(WorkbenchEmail(email), samRequestContext) { subject =>
        complete(
          managedGroupService
            .addSubjectToPolicy(managedGroup.resourceId, accessPolicyName, subject, samRequestContext)
            .map(_ => StatusCodes.NoContent)
        )
      }
    }

  private def handleDeleteEmailFromPolicy(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      email: String,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), samUser.id, samRequestContext) {
      withSubject(WorkbenchEmail(email), samRequestContext) { subject =>
        complete(
          managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, accessPolicyName, subject, samRequestContext).map(_ => StatusCodes.NoContent)
        )
      }
    }

  private def handleRequestAccess(managedGroup: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): Route =
    requireAction(managedGroup, SamResourceActions.notifyAdmins, samUser.id, samRequestContext) {
      complete(managedGroupService.requestAccess(managedGroup.resourceId, samUser.id, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  private def handleSetAccessInstructions(
      managedGroup: FullyQualifiedResourceId,
      accessInstructions: ManagedGroupAccessInstructions,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.setAccessInstructions, samUser.id, samRequestContext) {
      complete(managedGroupService.setAccessInstructions(managedGroup.resourceId, accessInstructions.value, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  private def handleGetAccessInstructions(managedGroup: FullyQualifiedResourceId, samRequestContext: SamRequestContext): Route =
    complete(managedGroupService.getAccessInstructions(managedGroup.resourceId, samRequestContext).map {
      case Some(accessInstructions) => StatusCodes.OK -> Option(accessInstructions)
      case None => StatusCodes.NoContent -> None
    })
}
