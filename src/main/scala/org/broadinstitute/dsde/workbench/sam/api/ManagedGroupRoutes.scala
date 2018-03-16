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
      pathEndOrSingleSlash {
        get {
          handleGetGroup(groupId)
        } ~
        post {
          handlePostGroup(groupId, userInfo)
        } ~
        delete {
          handleDeleteGroup(groupId, userInfo)
        }
      } ~
      path("members") {
        get {
          handleListMemberEmails(groupId, userInfo)
        } ~
        put {
          handleOverwriteMemberEmails(groupId, userInfo)
        }
      } ~
      path("admins") {
        get {
          handleListAdminEmails(groupId, userInfo)
        } ~
        put {
          handleOverwriteAdminEmails(groupId, userInfo)
        }
      }
    }
  }

  private def handleGetGroup(groupId: String): Route = {
    complete (
      managedGroupService.loadManagedGroup(ResourceId(groupId)).map {
        case Some(response) => StatusCodes.OK -> response
        case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
      }
    )
  }

  private def handlePostGroup(groupId: String, userInfo: UserInfo): Route = {
    complete(managedGroupService.createManagedGroup(ResourceId(groupId), userInfo).map(_ => StatusCodes.Created))
  }

  private def handleDeleteGroup(groupId: String, userInfo: UserInfo): Route = {
    val resource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    requireAction(resource, SamResourceActions.delete, userInfo) {
      complete(managedGroupService.deleteManagedGroup(ResourceId(groupId)).map(_ => StatusCodes.NoContent))
    }
  }

  private def handleListAdminEmails(groupId: String, userInfo: UserInfo): Route = {
    val resource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    // TODO: What is the correct Action that's needed here?  "readPolicies" or "read_policy::admin"?
    requireAction(resource, SamResourceActions.readPolicy(AccessPolicyName(ManagedGroupService.adminValue)), userInfo) {
      complete(
        managedGroupService.listAdminEmails(ResourceId(groupId)).map(StatusCodes.OK -> _)
      )
    }
  }

  private def handleListMemberEmails(groupId: String, userInfo: UserInfo) = {
    val resource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    // TODO: What is the correct Action that's needed here?  "readPolicies" or "read_policy::admin"?
    requireAction(resource, SamResourceActions.readPolicy(AccessPolicyName(ManagedGroupService.adminValue)), userInfo) {
      complete(
        managedGroupService.listMemberEmails(ResourceId(groupId)).map(StatusCodes.OK -> _)
      )
    }
  }

  private def handleOverwriteMemberEmails(groupId: String, userInfo: UserInfo) = {
    val resource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    requireAction(resource, SamResourceActions.alterPolicies, userInfo) {
      entity(as[Set[WorkbenchEmail]]) { members =>
        complete(
          managedGroupService.overwriteMemberEmails(ResourceId(groupId), members).map(_ => StatusCodes.Created)
        )
      }
    }
  }

  private def handleOverwriteAdminEmails(groupId: String, userInfo: UserInfo) = ???
}
