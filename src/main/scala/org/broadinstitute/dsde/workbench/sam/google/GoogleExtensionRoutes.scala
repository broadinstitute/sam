package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchUser}
import org.broadinstitute.dsde.workbench.sam.api.{ExtensionRoutes, UserInfoDirectives}
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait GoogleExtensionRoutes extends ExtensionRoutes with UserInfoDirectives {
  implicit val executionContext: ExecutionContext
  val googleExtensions: GoogleExtensions

  override def extensionRoutes: server.Route =
    //  THIS FIRST ROUTE IS DEPRECATED, put any new routes under the pathPrefix("google") below
    pathPrefix("user") {
      requireUserInfo { userInfo =>
        path("petServiceAccount") {
          get {
            complete {
              googleExtensions.createUserPetServiceAccount(WorkbenchUser(userInfo.userId, userInfo.userEmail)).map { petSA =>
                StatusCodes.OK -> petSA
              }
            }
          }
        }
      }
    } ~
    pathPrefix("google") {
      requireUserInfo { userInfo =>
        pathPrefix("user") {
          path("petServiceAccount") {
            get {
              complete {
                googleExtensions.createUserPetServiceAccount(WorkbenchUser(userInfo.userId, userInfo.userEmail)).map { petSA =>
                  StatusCodes.OK -> petSA
                }
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
                }
              }
            }
          }
      }
    }
}
