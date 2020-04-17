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
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.completeWithTrace
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

/**
  * Created by gpolumbo on 2/20/2018.
  */
trait ManagedGroupRoutes extends UserInfoDirectives with SecurityDirectives with SamModelDirectives {
  implicit val executionContext: ExecutionContext

  val managedGroupService: ManagedGroupService

  def groupRoutes: server.Route = requireUserInfo { userInfo =>
    (pathPrefix("groups" / "v1") | pathPrefix("group")) {
      pathPrefix(Segment) { groupId =>
        val managedGroup = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))

        pathEndOrSingleSlash {
          get {
            handleGetGroup(managedGroup.resourceId)
          } ~ post {
            handleCreateGroup(managedGroup.resourceId, userInfo)
          } ~ delete {
            handleDeleteGroup(managedGroup, userInfo)
          }
        } ~ pathPrefix("requestAccess") {
          post {
            handleRequestAccess(managedGroup, userInfo)
          }
        } ~ path("accessInstructions") {
          put {
            entity(as[ManagedGroupAccessInstructions]) { accessInstructions =>
              handleSetAccessInstructions(managedGroup, accessInstructions, userInfo)
            }
          } ~ get {
            handleGetAccessInstructions(managedGroup)
          }
        } ~ pathPrefix(Segment) { policyName =>
          val accessPolicyName = ManagedGroupService.getPolicyName(policyName)

          pathEndOrSingleSlash {
            get {
              handleListEmails(managedGroup, accessPolicyName, userInfo)
            } ~ put {
              handleOverwriteEmails(managedGroup, accessPolicyName, userInfo)
            }
          } ~ pathPrefix(Segment) { email =>
            pathEndOrSingleSlash {
              put {
                handleAddEmailToPolicy(managedGroup, accessPolicyName, email, userInfo)
              } ~ delete {
                handleDeleteEmailFromPolicy(managedGroup, accessPolicyName, email, userInfo)
              }
            }
          }
        }
      }
    } ~ (pathPrefix("groups" / "v1") | pathPrefix("groups")) {
      pathEndOrSingleSlash {
        get {
          handleListGroups(userInfo)
        }
      }
    }
  }

  private def handleListGroups(userInfo: UserInfo): Route =
    completeWithTrace(span => managedGroupService.listGroups(userInfo.userId, span).map(StatusCodes.OK -> _))

  private def handleGetGroup(resourceId: ResourceId): Route =
    completeWithTrace(span =>
      managedGroupService.loadManagedGroup(resourceId, span).flatMap {
        case Some(response) => IO.pure(StatusCodes.OK -> response)
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))
      }
    )

  private def handleCreateGroup(resourceId: ResourceId, userInfo: UserInfo): Route =
    completeWithTrace(span => managedGroupService.createManagedGroup(resourceId, userInfo, parentSpan = span).map(_ => StatusCodes.Created))

  private def handleDeleteGroup(managedGroup: FullyQualifiedResourceId, userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.delete, userInfo.userId) {
      completeWithTrace(span => managedGroupService.deleteManagedGroup(managedGroup.resourceId, span).map(_ => StatusCodes.NoContent))
    }

  private def handleListEmails(managedGroup: FullyQualifiedResourceId, accessPolicyName: ManagedGroupPolicyName, userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.readPolicy(accessPolicyName), userInfo.userId) {
      completeWithTrace(span =>
        managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, accessPolicyName, span).map(x => StatusCodes.OK -> x.toSet)
      )
    }

  private def handleOverwriteEmails(managedGroup: FullyQualifiedResourceId, accessPolicyName: ManagedGroupPolicyName, userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo.userId) {
      entity(as[Set[WorkbenchEmail]]) { members =>
        completeWithTrace(span =>
          managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, accessPolicyName, members, span).map(_ => StatusCodes.Created)
        )
      }
    }

  private def handleAddEmailToPolicy(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      email: String,
      userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo.userId) {
      withSubject(WorkbenchEmail(email)) { subject =>
        completeWithTrace(span =>
          managedGroupService.addSubjectToPolicy(managedGroup.resourceId, accessPolicyName, subject, span).map(_ => StatusCodes.NoContent)
        )
      }
    }

  private def handleDeleteEmailFromPolicy(
      managedGroup: FullyQualifiedResourceId,
      accessPolicyName: ManagedGroupPolicyName,
      email: String,
      userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo.userId) {
      withSubject(WorkbenchEmail(email)) { subject =>
        completeWithTrace(span =>
          managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, accessPolicyName, subject, span).map(_ => StatusCodes.NoContent)
        )
      }
    }

  private def handleRequestAccess(managedGroup: FullyQualifiedResourceId, userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.notifyAdmins, userInfo.userId) {
      completeWithTrace(span =>
        managedGroupService.requestAccess(managedGroup.resourceId, userInfo.userId, span).map(_ => StatusCodes.NoContent)
      )
    }

  private def handleSetAccessInstructions(
      managedGroup: FullyQualifiedResourceId,
      accessInstructions: ManagedGroupAccessInstructions,
      userInfo: UserInfo): Route =
    requireAction(managedGroup, SamResourceActions.setAccessInstructions, userInfo.userId) {
      completeWithTrace(span =>
        managedGroupService.setAccessInstructions(managedGroup.resourceId, accessInstructions.value, span).map(_ => StatusCodes.NoContent)
      )
    }

  private def handleGetAccessInstructions(managedGroup: FullyQualifiedResourceId): Route =
    completeWithTrace(span =>
      managedGroupService.getAccessInstructions(managedGroup.resourceId, span).map {
        case Some(accessInstructions) => StatusCodes.OK -> Option(accessInstructions)
        case None => StatusCodes.NoContent -> None
      }
    )
}
