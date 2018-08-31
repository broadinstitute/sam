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
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.ManagedGroupPolicyName
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
        val managedGroup = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))

        pathEndOrSingleSlash {
          get {
            handleGetGroup(managedGroup)
          } ~
            post {
              handleCreateGroup(managedGroup, userInfo)
            } ~
            delete {
              handleDeleteGroup(managedGroup, userInfo)
            }
        } ~
        pathPrefix("requestAccess") {
          post {
            handleRequestAccess(managedGroup, userInfo)
          }
        } ~
        pathPrefix(Segment) { policyName =>
          val accessPolicyName = ManagedGroupService.getPolicyName(policyName)

          pathEndOrSingleSlash {
            get {
              handleListEmails(managedGroup, accessPolicyName, userInfo)
            } ~
              put {
                handleOverwriteEmails(managedGroup, accessPolicyName, userInfo)
              }
          } ~
          pathPrefix(Segment) { email =>
            pathEndOrSingleSlash {
              put {
                handleAddEmailToPolicy(managedGroup, accessPolicyName, email, userInfo)
              } ~
              delete {
                handleDeleteEmailFromPolicy(managedGroup, accessPolicyName, email, userInfo)
              }
            }
          }
        }
      }
    } ~
    (pathPrefix("groups" / "v1") | pathPrefix("groups")) {
      pathEndOrSingleSlash {
        get {
          handleListGroups(userInfo)
        }
      }
    }
  }

  def adminGroupRoutes: server.Route =
    pathPrefix("admin") {
      requireUserInfo { userInfo =>
        asWorkbenchAdmin(userInfo) {
          (pathPrefix("groups" / "v1") | pathPrefix("group")) {
            pathPrefix(Segment) { groupId =>
              val managedGroup = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
              pathPrefix("setAccessInstructions") {
                post {
                  entity(as[ManagedGroupAccessInstructions]) { accessInstructions =>
                    handleSetAccessInstructions(managedGroup, accessInstructions)
                  }
                }
              }
            }
          }
        }
      }
    }


  private def handleListGroups(userInfo: UserInfo): Route = {
    complete(managedGroupService.listGroups(userInfo.userId).map(StatusCodes.OK -> _))
  }

  private def handleGetGroup(managedGroup: Resource): Route = {
    complete (
      managedGroupService.loadManagedGroup(managedGroup.resourceId).map {
        case Some(response) => StatusCodes.OK -> response
        case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found"))
      }
    )
  }

  private def handleCreateGroup(managedGroup: Resource, userInfo: UserInfo): Route = {
    complete(managedGroupService.createManagedGroup(managedGroup.resourceId, userInfo).map(_ => StatusCodes.Created))
  }

  private def handleDeleteGroup(managedGroup: Resource, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.delete, userInfo) {
      complete(managedGroupService.deleteManagedGroup(managedGroup.resourceId).map(_ => StatusCodes.NoContent))
    }
  }

  private def handleListEmails(managedGroup: Resource, accessPolicyName: ManagedGroupPolicyName, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.readPolicy(accessPolicyName), userInfo) {
      complete(
        managedGroupService.listPolicyMemberEmails(managedGroup.resourceId, accessPolicyName).map(StatusCodes.OK -> _)
      )
    }
  }

  private def handleOverwriteEmails(managedGroup: Resource, accessPolicyName: ManagedGroupPolicyName, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo) {
      entity(as[Set[WorkbenchEmail]]) { members =>
        complete(
          managedGroupService.overwritePolicyMemberEmails(managedGroup.resourceId, accessPolicyName, members).map(_ => StatusCodes.Created)
        )
      }
    }
  }

  private def handleAddEmailToPolicy(managedGroup: Resource, accessPolicyName: ManagedGroupPolicyName, email: String, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo) {
      withSubject(WorkbenchEmail(email)) { subject =>
        complete(
          managedGroupService.addSubjectToPolicy(managedGroup.resourceId, accessPolicyName, subject).map(_ => StatusCodes.NoContent)
        )
      }
    }
  }

  private def handleDeleteEmailFromPolicy(managedGroup: Resource, accessPolicyName: ManagedGroupPolicyName, email: String, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.sharePolicy(accessPolicyName), userInfo) {
      withSubject(WorkbenchEmail(email)) { subject =>
        complete(
          managedGroupService.removeSubjectFromPolicy(managedGroup.resourceId, accessPolicyName, subject).map(_ => StatusCodes.NoContent)
        )
      }
    }
  }

  private def handleRequestAccess(managedGroup: Resource, userInfo: UserInfo): Route = {
    requireAction(managedGroup, SamResourceActions.notifyAdmins, userInfo) {
      complete(
        managedGroupService.requestAccess(managedGroup.resourceId, userInfo.userId).map {
          case Some(accessInstructions) => StatusCodes.OK -> Option(accessInstructions)
          case None => StatusCodes.NoContent -> None
        }
      )
    }
  }

  private def handleSetAccessInstructions(managedGroup: Resource, accessInstructions: ManagedGroupAccessInstructions): Route = {
    complete(
      managedGroupService.setAccessInstructions(managedGroup.resourceId, accessInstructions.instructions).map(_ => StatusCodes.NoContent)
    )
  }
}
