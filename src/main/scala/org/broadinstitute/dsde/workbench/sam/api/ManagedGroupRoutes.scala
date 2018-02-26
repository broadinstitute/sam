package org.broadinstitute.dsde.workbench.sam.api

// package object for implicit ErrorReportSource
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.sam._

// IntelliJ may highlight these imports as unused, but they're needed for the json formatting to work properly
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
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
        complete(handleGetGroup(groupId))
      } ~
      post {
        complete(handlePostGroup(groupId, userInfo))
      } ~
      delete {
        complete(handleDeleteGroup(groupId))
      }
    }
  }

  private def handleDeleteGroup(groupId: String) = {
    managedGroupService.deleteManagedGroup(ResourceId(groupId)).map(_ => StatusCodes.NoContent)
  }

  private def handlePostGroup(groupId: String, userInfo: UserInfo) = {
    managedGroupService.createManagedGroup(ResourceId(groupId), userInfo).map(_ => StatusCodes.NoContent)
  }

  private def handleGetGroup(groupId: String) = {
    managedGroupService.loadManagedGroup(ResourceId(groupId)).map {
      case Some(response) => StatusCodes.OK -> response.asSerializable
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
    }
  }
}
