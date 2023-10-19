package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.TosService

import scala.concurrent.ExecutionContext

trait TermsOfServiceRoutes {
  val tosService: TosService
  implicit val executionContext: ExecutionContext

  @deprecated("Being replaced by REST-ful termsOfService routes")
  def oldTermsOfServiceRoutes: server.Route =
    pathPrefix("tos") {
      path("text") {
        pathEndOrSingleSlash {
          get {
            complete(tosService.termsOfServiceText)
          }
        }
      }
    } ~
      pathPrefix("privacy") {
        path("text") {
          pathEndOrSingleSlash {
            get {
              complete(tosService.privacyPolicyText)
            }
          }
        }
      }

  def termsOfServiceRoutes: server.Route =
    pathPrefix("termsOfService") {
      pathPrefix("v1") { // api/termsOfService/v1
        pathEndOrSingleSlash {
          get {
            complete(StatusCodes.NotImplemented)
          }
        } ~
          pathPrefix("docs") { // api/termsOfService/v1/docs
            pathEndOrSingleSlash {
              get {
                complete(StatusCodes.NotImplemented)
              }
            } ~
              pathPrefix("redirect") { // api/termsOfService/v1/docs/redirect
                pathEndOrSingleSlash {
                  get {
                    complete(StatusCodes.NotImplemented)
                  }
                }
              }
          } ~
          pathPrefix("user") { // api/termsOfService/v1/user
            pathPrefix("self") { // api/termsOfService/v1/user/self
              pathEndOrSingleSlash {
                get {
                  complete(StatusCodes.NotImplemented)
                }
              } ~
                pathPrefix("accept") { // api/termsOfService/v1/user/accept
                  pathEndOrSingleSlash {
                    put {
                      complete(StatusCodes.NotImplemented)
                    }
                  }
                } ~
                pathPrefix("reject") { // api/termsOfService/v1/user/reject
                  pathEndOrSingleSlash {
                    put {
                      complete(StatusCodes.NotImplemented)
                    }
                  }
                }
            } ~
              // The {user_id} route must be last otherwise it will try to parse the other routes incorrectly as user id's
              pathPrefix(Segment) { userId => // api/termsOfService/v1/user/{userId}
                pathEndOrSingleSlash {
                  get {
                    complete(StatusCodes.NotImplemented)
                  }
                } ~
                  pathPrefix("history") {
                    pathEndOrSingleSlash {
                      get {
                        complete(StatusCodes.NotImplemented)
                      }
                    }
                  }
              }
          }
      }
    }
}
