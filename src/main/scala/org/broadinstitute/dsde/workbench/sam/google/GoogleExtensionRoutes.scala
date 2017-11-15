package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.WorkbenchUser
import org.broadinstitute.dsde.workbench.sam.api.{ExtensionRoutes, UserInfoDirectives}

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
        }
      }
    }


}
