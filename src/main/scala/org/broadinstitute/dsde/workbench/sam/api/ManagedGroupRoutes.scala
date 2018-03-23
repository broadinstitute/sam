package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{ResourceId, _}
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

/**
  * Created by gpolumbo on 2/20/2018.
  */
trait ManagedGroupRoutes extends UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext

  val managedGroupService: ManagedGroupService

  def groupRoutes: server.Route = requireUserInfo { userInfo =>
    pathPrefix("group" / Segment) { groupId =>
      val managedGroup = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))

      pathEndOrSingleSlash {
        get {
          handleGetGroup(managedGroup)
        } ~
        post {
          handlePostGroup(managedGroup, userInfo)
        } ~
        delete {
          handleDeleteGroup(managedGroup, userInfo)
        }
      } ~
      pathPrefix(Segment) { policyName =>
        val accessPolicyName = AccessPolicyName(if (policyName == "members") ManagedGroupService.memberValue else ManagedGroupService.adminValue)

        pathEndOrSingleSlash {
          get {
            handleListEmails(managedGroup, accessPolicyName, userInfo)
          } ~
          put {
            handleOverwriteEmails(managedGroup, accessPolicyName, userInfo)
          }
        }
      }
    }
  }

  private def handleGetGroup(managedGroup: Resource): Route = {
    complete (
      managedGroupService.loadManagedGroup(managedGroup.resourceId).map {
        case Some(response) => StatusCodes.OK -> response
        case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
      }
    )
  }

  private def handlePostGroup(managedGroup: Resource, userInfo: UserInfo): Route = {
    complete(managedGroupService.createManagedGroup(managedGroup.resourceId, userInfo).map(_ => StatusCodes.Created))
  }

  private def handleDeleteGroup(managedGroup: Resource, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.delete, userInfo) {
      complete(managedGroupService.deleteManagedGroup(managedGroup.resourceId).map(_ => StatusCodes.NoContent))
    }
  }

  private def handleListEmails(managedGroup: Resource, policyName: AccessPolicyName, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.readPolicy(AccessPolicyName(ManagedGroupService.adminValue)), userInfo) {
      complete(
        managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, policyName).map(StatusCodes.OK -> _)
      )
    }
  }

  private def handleOverwriteEmails(managedGroup: Resource, policyName: AccessPolicyName, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.sharePolicy(policyName), userInfo) {
      entity(as[Set[WorkbenchEmail]]) { members =>
        complete(
          managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, policyName, members).map(_ => StatusCodes.Created)
        )
      }
    }
  }
}
