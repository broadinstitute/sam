package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.{pathPrefix, _}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.TosService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.util.matching.Regex

trait TermsOfServiceRoutes extends SamUserDirectives {
  val tosService: TosService
  implicit val executionContext: ExecutionContext
  private val samUserIdPattern: Regex = "^[a-zA-Z0-9]+$".r

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

  def publicTermsOfServiceRoutes: server.Route =
    pathPrefix("termsOfService") {
      pathPrefix("v1") { // api/termsOfService/v1
        pathEndOrSingleSlash {
          get {
            complete(tosService.getTosConfig())
          }
        } ~
        pathPrefix("docs") { // api/termsOfService/v1/docs
          pathEndOrSingleSlash {
            get {
              parameters("doc".as[String].?) { (doc: Option[String]) =>
                val docSet = doc.map(_.split(",").toSet).getOrElse(Set.empty)
                complete(tosService.getTosText(docSet))
              }
            }
          } ~
          pathPrefix("redirect") { // api/termsOfService/v1/docs/redirect
            pathEndOrSingleSlash {
              get {
                complete(StatusCodes.NotImplemented)
              }
            }
          }
        }
      }
    }

  def userTermsOfServiceRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("termsOfService") {
      pathPrefix("v1") {
        pathPrefix("user") { // api/termsOfService/v1/user
          pathPrefix("self") { // api/termsOfService/v1/user/self
            pathEndOrSingleSlash {
              get {
                complete {
                  tosService.getTermsOfServiceDetailsForUser(samUser.id, samRequestContext)
                }
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
            validate(samUserIdPattern.matches(userId), "User ID must be alpha numeric") {
              val requestUserId = WorkbenchUserId(userId)
              pathEndOrSingleSlash {
                get {
                  complete {
                    tosService.getTermsOfServiceDetailsForUser(requestUserId, samRequestContext)
                  }
                }
              } ~
              pathPrefix("history") { // api/termsOfService/v1/user/{userId}/history
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
}
