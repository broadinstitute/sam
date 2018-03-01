package org.broadinstitute.dsde.workbench.sam.api

import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.sam._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.ResourceId
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService

import scala.concurrent.ExecutionContext

/**
  * Created by gpolumbo on 2/20/2018.
  */
trait ManagedGroupRoutes extends UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext

  val managedGroupService: ManagedGroupService

  def groupRoutes: server.Route = requireUserInfo { userInfo =>
    path("group" / Segment) { groupId =>
      get {
        handleGetGroup(groupId)
      } ~
      post {
        handlePostGroup(groupId, userInfo)
      } ~
      delete {
        handleDeleteGroup(groupId, userInfo)
      }
    }
  }

  private def handleGetGroup(groupId: String) = {
    complete (
      managedGroupService.loadManagedGroup(ResourceId(groupId)).map {
        case Some(response) => StatusCodes.OK -> response
        case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
      }
    )
  }

  private def handlePostGroup(groupId: String, userInfo: UserInfo) = {
    complete(managedGroupService.createManagedGroup(ResourceId(groupId), userInfo).map(_ => StatusCodes.Created))
  }

  private def handleDeleteGroup(groupId: String, userInfo: UserInfo) = {
    val resource = Resource(ManagedGroupService.ManagedGroupTypeName, ResourceId(groupId))
    requireAction(resource, SamResourceActions.delete, userInfo) {
      delete {
        complete(managedGroupService.deleteManagedGroup(ResourceId(groupId)).map(_ => StatusCodes.NoContent))
      }
    }
  }
}
