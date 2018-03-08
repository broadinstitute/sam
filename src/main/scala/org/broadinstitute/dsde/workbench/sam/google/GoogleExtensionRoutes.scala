package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.{ExtensionRoutes, SecurityDirectives, UserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait GoogleExtensionRoutes extends ExtensionRoutes with UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext
  val googleExtensions: GoogleExtensions

  override def extensionRoutes: server.Route =
    //  THIS FIRST ROUTE IS DEPRECATED, put any new routes under the pathPrefix("google") below
    pathPrefix("user") {
      requireUserInfo { userInfo =>
        path("petServiceAccount") {
          get { // NOTE: This endpoint is not visible in Swagger
            complete {
              googleExtensions.createUserPetServiceAccount(WorkbenchUser(userInfo.userId, userInfo.userEmail)).map { petSA =>
                StatusCodes.OK -> petSA.serviceAccount.email
              }
            }
          } ~
          delete { // NOTE: This endpoint is not visible in Swagger
            complete {
              googleExtensions.deleteUserPetServiceAccount(userInfo.userId).map(_ => StatusCodes.NoContent)
            }
          }
        }
      }
    } ~
    pathPrefix("google") {
      requireUserInfo { userInfo =>
        path("petServiceAccount" / Segment / Segment ) { (project, userEmail) =>
          get {
            requireAction(Resource(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId), GoogleExtensions.getPetPrivateKeyAction, userInfo) {
              complete {
                import spray.json._
                googleExtensions.getPetServiceAccountKey(WorkbenchEmail(userEmail), GoogleProject(project)) map {
                  // parse json to ensure it is json and tells akka http the right content-type
                  case Some(key) => StatusCodes.OK -> key.parseJson
                  case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                }
              }
            }
          }
        } ~
        pathPrefix("user") {
          pathPrefix("petServiceAccount" / Segment) { project =>
            pathPrefix("key") {
              get {
                complete {
                  import spray.json._
                  // parse json to ensure it is json and tells akka http the right content-type
                  googleExtensions.getPetServiceAccountKey(WorkbenchUser(userInfo.userId, userInfo.userEmail), GoogleProject(project)).map(key => StatusCodes.OK -> key.parseJson)
                }
              } ~
              path(Segment) { keyId =>
                delete {
                  complete {
                    googleExtensions.removePetServiceAccountKey(userInfo.userId, GoogleProject(project), ServiceAccountKeyId(keyId)).map(_ => StatusCodes.NoContent)
                  }
                }
              }
            } ~
            pathEnd {
              get {
                complete {
                  googleExtensions.createUserPetServiceAccount(WorkbenchUser(userInfo.userId, userInfo.userEmail), GoogleProject(project)).map { petSA =>
                    StatusCodes.OK -> petSA.serviceAccount.email
                  }
                }
              } ~
              delete { // NOTE: This endpoint is not visible in Swagger
                complete {
                  googleExtensions.deleteUserPetServiceAccount(userInfo.userId, GoogleProject(project)).map(_ => StatusCodes.NoContent)
                }
              }
            }
          } ~
          path("proxyGroup" / Segment) { targetUserEmail =>
            complete {
              googleExtensions.getUserProxy(WorkbenchEmail(targetUserEmail)).map {
                case Some(proxyEmail) =>  StatusCodes.OK -> Option(proxyEmail)
                case _ =>  StatusCodes.NotFound -> None
              }
            }
          }
        } ~
        pathPrefix("resource") {
          path(Segment / Segment / Segment / "sync") { (resourceTypeName, resourceId, accessPolicyName) =>
            val resource = Resource(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
            val resourceAndPolicyName = ResourceAndPolicyName(resource, AccessPolicyName(accessPolicyName))
            pathEndOrSingleSlash {
              post {
                complete {
                  import GoogleModelJsonSupport._
                  googleExtensions.synchronizeGroupMembers(resourceAndPolicyName).map { syncReport =>
                    StatusCodes.OK -> syncReport
                  }
                }
              } ~
              get {
                complete {
                  googleExtensions.getSynchronizedDate(resourceAndPolicyName).map {
                    case Some(date) => StatusCodes.OK -> Option(GroupSyncResponse(date.toString))
                    case None => StatusCodes.NoContent -> None
                  }
                }
              }
            }
          }
        } ~
        path("group" / Segment / "sync") { groupName =>
          val groupId = WorkbenchGroupName(groupName)
          post {
            complete {
              import GoogleModelJsonSupport._
              googleExtensions.synchronizeGroupMembers(groupId).map { syncReport =>
                StatusCodes.OK -> syncReport
              }
            }
          }
        }
      }
    }
}
