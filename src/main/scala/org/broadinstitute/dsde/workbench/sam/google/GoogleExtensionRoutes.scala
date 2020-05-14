package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUser}
import org.broadinstitute.dsde.workbench.sam.api.{ExtensionRoutes, SamModelDirectives, SecurityDirectives, UserInfoDirectives, ioMarshaller}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import spray.json.DefaultJsonProtocol._
import spray.json.JsString

import scala.concurrent.ExecutionContext

trait GoogleExtensionRoutes extends ExtensionRoutes with UserInfoDirectives with SecurityDirectives with SamModelDirectives {
  implicit val executionContext: ExecutionContext
  val googleExtensions: GoogleExtensions
  val googleGroupSynchronizer: GoogleGroupSynchronizer

  override def extensionRoutes: server.Route =
    (pathPrefix("google" / "v1") | pathPrefix("google")) {
      withSamRequestContext { samRequestContext =>
        requireUserInfo(samRequestContext) { userInfo =>
          path("petServiceAccount" / Segment / Segment) { (project, userEmail) =>
            get {
              requireAction(
                FullyQualifiedResourceId(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId),
                GoogleExtensions.getPetPrivateKeyAction,
                userInfo.userId,
                samRequestContext) {
                complete {
                  import spray.json._
                  googleExtensions.getPetServiceAccountKey(WorkbenchEmail(userEmail), GoogleProject(project), samRequestContext) map {
                    // parse json to ensure it is json and tells akka http the right content-type
                    case Some(key) => StatusCodes.OK -> key.parseJson
                    case None =>
                      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                  }
                }
              }
            }
          } ~
            pathPrefix("user" / "petServiceAccount") {
              pathPrefix("key") {
                pathEndOrSingleSlash {
                  get {
                    complete {
                      import spray.json._
                      googleExtensions
                        .getArbitraryPetServiceAccountKey(WorkbenchUser(userInfo.userId, None, userInfo.userEmail, None), samRequestContext)
                        .map(key => StatusCodes.OK -> key.parseJson)
                    }
                  }
                }
              } ~
                pathPrefix("token") {
                  pathEndOrSingleSlash {
                    post {
                      entity(as[Set[String]]) { scopes =>
                        complete {
                          googleExtensions.getArbitraryPetServiceAccountToken(WorkbenchUser(userInfo.userId, None, userInfo.userEmail, None), scopes, samRequestContext).map { token =>
                            StatusCodes.OK -> JsString(token)
                          }
                        }
                      }
                    }
                  }
                } ~
                pathPrefix(Segment) { project =>
                  pathPrefix("key") {
                    get {
                      complete {
                        import spray.json._
                        // parse json to ensure it is json and tells akka http the right content-type
                        googleExtensions
                          .getPetServiceAccountKey(WorkbenchUser(userInfo.userId, None, userInfo.userEmail, None), GoogleProject(project), samRequestContext)
                          .map { key =>
                            StatusCodes.OK -> key.parseJson
                          }
                      }
                    } ~
                      path(Segment) { keyId =>
                        delete {
                          complete {
                            googleExtensions
                              .removePetServiceAccountKey(userInfo.userId, GoogleProject(project), ServiceAccountKeyId(keyId), samRequestContext)
                              .map(_ => StatusCodes.NoContent)
                          }
                        }
                      }
                  } ~
                    pathPrefix("token") {
                      post {
                        entity(as[Set[String]]) { scopes =>
                          complete {
                            googleExtensions
                              .getPetServiceAccountToken(WorkbenchUser(userInfo.userId, None, userInfo.userEmail, None), GoogleProject(project), scopes, samRequestContext)
                              .map { token =>
                                StatusCodes.OK -> JsString(token)
                              }
                          }
                        }
                      }
                    } ~
                    pathEnd {
                      get {
                        complete {
                          googleExtensions.createUserPetServiceAccount(WorkbenchUser(userInfo.userId, None, userInfo.userEmail, None), GoogleProject(project), samRequestContext).map {
                            petSA =>
                              StatusCodes.OK -> petSA.serviceAccount.email
                          }
                        }
                      } ~
                        delete { // NOTE: This endpoint is not visible in Swagger
                          complete {
                            googleExtensions.deleteUserPetServiceAccount(userInfo.userId, GoogleProject(project), samRequestContext).map(_ => StatusCodes.NoContent)
                          }
                        }
                    }
                }
            } ~
            pathPrefix("user") {
              path("proxyGroup" / Segment) { targetUserEmail =>
                complete {
                  googleExtensions.getUserProxy(WorkbenchEmail(targetUserEmail), samRequestContext).map {
                    case Some(proxyEmail) => StatusCodes.OK -> Option(proxyEmail)
                    case _ => StatusCodes.NotFound -> None
                  }
                }
              }
            } ~
            pathPrefix("resource") {
              path(Segment / Segment / Segment / "sync") { (resourceTypeName, resourceId, accessPolicyName) =>
                val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
                val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(accessPolicyName))
                pathEndOrSingleSlash {
                  post {
                    complete {
                      import GoogleModelJsonSupport._
                      googleGroupSynchronizer.synchronizeGroupMembers(policyId, samRequestContext = samRequestContext).map { syncReport =>
                        StatusCodes.OK -> syncReport
                      }
                    }
                  } ~
                    get {
                      complete {
                        googleExtensions.getSynchronizedState(policyId, samRequestContext).map {
                          case Some(syncState) => StatusCodes.OK -> Option(syncState)
                          case None => StatusCodes.NoContent -> None
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
