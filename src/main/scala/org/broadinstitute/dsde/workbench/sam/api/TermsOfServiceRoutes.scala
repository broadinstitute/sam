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

trait TermsOfServiceRoutes extends SamUserDirectives with SamRequestContextDirectives {
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
      pathPrefix("v1") { // /termsOfService/v1
        pathEndOrSingleSlash {
          get {
            complete(tosService.getTermsOfServiceConfig())
          }
        } ~
        pathPrefix("docs") { // /termsOfService/v1/docs
          pathEndOrSingleSlash {
            get {
              parameters("doc".as[String].?) { (doc: Option[String]) =>
                val docSet = doc.map(_.split(",").toSet).getOrElse(Set.empty)
                if (docSet.subsetOf(Set(tosService.termsOfServiceTextKey, tosService.privacyPolicyTextKey)))
                  complete(tosService.getTermsOfServiceTexts(docSet))
                else
                  complete(StatusCodes.BadRequest)
              }
            }
          } ~
          pathPrefix("redirect") { // /termsOfService/v1/docs/redirect
            pathEndOrSingleSlash {
              get {
                complete(StatusCodes.NotImplemented)
              }
            }
          }
        }
      }
    }

  def userTermsOfServiceRoutes(samRequestContextWithoutUser: SamRequestContext): server.Route =
    pathPrefix("termsOfService") {
      pathPrefix("v1") {
        pathPrefix("user") { // api/termsOfService/v1/user
          pathPrefix("self") { // api/termsOfService/v1/user/self
            withUserAllowInactive(samRequestContextWithoutUser) { samUser: SamUser =>
              val samRequestContext = samRequestContextWithoutUser.copy(samUser = Some(samUser))
              pathEndOrSingleSlash {
                getWithTelemetry(samRequestContext) {
                  rejectEmptyResponse {
                    complete {
                      tosService.getTermsOfServiceDetailsForUser(samUser.id, samRequestContext)
                    }
                  }
                }
              } ~
              pathPrefix("accept") { // api/termsOfService/v1/user/self/accept
                pathEndOrSingleSlash {
                  putWithTelemetry(samRequestContext) {
                    complete(tosService.acceptCurrentTermsOfService(samUser.id, samRequestContext).map(_ => StatusCodes.NoContent))
                  }
                }
              } ~
              pathPrefix("reject") { // api/termsOfService/v1/user/self/reject
                pathEndOrSingleSlash {
                  putWithTelemetry(samRequestContext) {
                    complete(tosService.rejectCurrentTermsOfService(samUser.id, samRequestContext).map(_ => StatusCodes.NoContent))
                  }
                }
              } ~
              pathPrefix("history") { // api/termsOfService/v1/user/self/history
                pathEndOrSingleSlash {
                  getWithTelemetry(samRequestContext) {
                    parameters("limit".as[Integer].withDefault(100)) { (limit: Int) =>
                      complete {
                        tosService.getTermsOfServiceHistoryForUser(samUser.id, samRequestContext, limit)
                      }
                    }
                  }
                }
              }
            }
          } ~
          // The {user_id} route must be last otherwise it will try to parse the other routes incorrectly as user id's
          pathPrefix(Segment) { userId => // api/termsOfService/v1/user/{userId}
            withActiveUser(samRequestContextWithoutUser) { samUser: SamUser =>
              val samRequestContext = samRequestContextWithoutUser.copy(samUser = Some(samUser))
              validate(samUserIdPattern.matches(userId), "User ID must be alpha numeric") {
                val requestUserId = WorkbenchUserId(userId)
                pathEndOrSingleSlash {
                  getWithTelemetry(samRequestContext, userIdParam(requestUserId)) {
                    rejectEmptyResponse {
                      complete {
                        tosService.getTermsOfServiceDetailsForUser(requestUserId, samRequestContext)
                      }
                    }
                  }
                } ~
                pathPrefix("history") { // api/termsOfService/v1/user/{userId}/history
                  pathEndOrSingleSlash {
                    getWithTelemetry(samRequestContext, userIdParam(requestUserId)) {
                      parameters("limit".as[Integer].withDefault(100)) { (limit: Int) =>
                        complete(tosService.getTermsOfServiceHistoryForUser(requestUserId, samRequestContext, limit))
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
}
