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
import org.broadinstitute.dsde.workbench.sam.model.api.{ManagedGroupAccessInstructions, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.ManagedGroupPolicyName
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.sam.model.api.ManagedGroupModelJsonSupport._

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
          getWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            handleGetGroup(managedGroup.resourceId, samRequestContext)
          } ~ postWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            handleCreateGroup(managedGroup.resourceId, samUser, samRequestContext)
          } ~ deleteWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            handleDeleteGroup(managedGroup, samUser, samRequestContext)
          }
        } ~ pathPrefix("requestAccess") {
          postWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            handleRequestAccess(managedGroup, samUser, samRequestContext)
          }
        } ~ path("accessInstructions") {
          putWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            entity(as[ManagedGroupAccessInstructions]) { accessInstructions =>
              handleSetAccessInstructions(managedGroup, accessInstructions, samUser, samRequestContext)
            }
          } ~ getWithTelemetry(samRequestContext, groupIdParam(managedGroup)) {
            handleGetAccessInstructions(managedGroup, samRequestContext)
          }
        } ~ pathPrefix(Segment) { policyName =>
          val accessPolicyName = ManagedGroupService.getPolicyName(policyName)
          pathEndOrSingleSlash {
            getWithTelemetry(samRequestContext, groupIdParam(managedGroup), policyNameParam(accessPolicyName)) {
              handleListEmails(managedGroup, accessPolicyName, samUser, samRequestContext)
            } ~ putWithTelemetry(samRequestContext, groupIdParam(managedGroup), policyNameParam(accessPolicyName)) {
              handleOverwriteEmails(managedGroup, accessPolicyName, samUser, samRequestContext)
            }
          } ~ pathPrefix(Segment) { email =>
            val workbenchEmail = WorkbenchEmail(email)
            pathEndOrSingleSlash {
              putWithTelemetry(samRequestContext, groupIdParam(managedGroup), policyNameParam(accessPolicyName), emailParam(workbenchEmail)) {
                handleAddEmailToPolicy(managedGroup, accessPolicyName, workbenchEmail, samUser, samRequestContext)
              } ~ deleteWithTelemetry(samRequestContext, groupIdParam(managedGroup), policyNameParam(accessPolicyName), emailParam(workbenchEmail)) {
                handleDeleteEmailFromPolicy(managedGroup, accessPolicyName, workbenchEmail, samUser, samRequestContext)
              }
            }
          }
        }
      }
    } ~ (pathPrefix("groups" / "v1") | pathPrefix("groups")) {
      pathEndOrSingleSlash {
        getWithTelemetry(samRequestContext) {
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
      email: WorkbenchEmail,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), samUser.id, samRequestContext) {
      withSubject(email, samRequestContext) { subject =>
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
      email: WorkbenchEmail,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), samUser.id, samRequestContext) {
      withSubject(email, samRequestContext) { subject =>
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
