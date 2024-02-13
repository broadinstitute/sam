package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.google2.GcsBlobName
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.api.{
  ExtensionRoutes,
  SamModelDirectives,
  SamRequestContextDirectives,
  SamUserDirectives,
  SecurityDirectives,
  ioMarshaller
}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.api.ActionServiceAccount._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import spray.json.JsString

import scala.concurrent.ExecutionContext

trait GoogleExtensionRoutes extends ExtensionRoutes with SamUserDirectives with SecurityDirectives with SamModelDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val googleExtensions: GoogleExtensions
  val googleGroupSynchronizer: GoogleGroupSynchronizer

  override def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    (pathPrefix("google" / "v1") | pathPrefix("google")) {
      // "Admin" routes, requires permission on cloud-extension/google resource
      pathPrefix("petServiceAccount") {
        requireAction(
          FullyQualifiedResourceId(CloudExtensions.resourceTypeName, GoogleExtensions.resourceId),
          GoogleExtensions.getPetPrivateKeyAction,
          samUser.id,
          samRequestContext
        ) {
          path(Segment / "key") { userEmail =>
            val email = WorkbenchEmail(userEmail)
            get {
              complete {
                import spray.json._
                googleExtensions.petServiceAccounts.getArbitraryPetServiceAccountKey(email, samRequestContext) map {
                  // parse json to ensure it is json and tells akka http the right content-type
                  case Some(key) => StatusCodes.OK -> key.parseJson
                  case None =>
                    throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                }
              }
            }
          } ~
            path(Segment / Segment) { (project, userEmail) =>
              val email = WorkbenchEmail(userEmail)
              val googleProject = GoogleProject(project)
              get {
                complete {
                  import spray.json._
                  googleExtensions.petServiceAccounts.getPetServiceAccountKey(email, googleProject, samRequestContext) map {
                    // parse json to ensure it is json and tells akka http the right content-type
                    case Some(key) => StatusCodes.OK -> key.parseJson
                    case None =>
                      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                  }
                }
              }
            }
        }
      } ~
        // "User" routes, acts on caller's identity
        pathPrefix("user" / "petServiceAccount") {
          pathPrefix("key") {
            pathEndOrSingleSlash {
              get {
                complete {
                  import spray.json._
                  googleExtensions.petServiceAccounts
                    .getArbitraryPetServiceAccountKey(samUser, samRequestContext)
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
                      googleExtensions.petServiceAccounts.getArbitraryPetServiceAccountToken(samUser, scopes, samRequestContext).map { token =>
                        StatusCodes.OK -> JsString(token)
                      }
                    }
                  }
                }
              }
            } ~
            pathPrefix(Segment) { project =>
              val projectResourceId = ResourceId(project)
              pathPrefix("key") {
                requireOneOfActionIfParentIsWorkspace(
                  FullyQualifiedResourceId(SamResourceTypes.googleProjectName, projectResourceId),
                  Set(SamResourceActions.createPet),
                  samUser.id,
                  samRequestContext
                ) {
                  get {
                    complete {
                      import spray.json._
                      // parse json to ensure it is json and tells akka http the right content-type
                      googleExtensions.petServiceAccounts
                        .getPetServiceAccountKey(samUser, GoogleProject(project), samRequestContext)
                        .map { key =>
                          StatusCodes.OK -> key.parseJson
                        }
                    }
                  }
                } ~
                  path(Segment) { keyId =>
                    val serviceAccountKeyId = ServiceAccountKeyId(keyId)
                    delete {
                      complete {
                        googleExtensions.petServiceAccounts
                          .removePetServiceAccountKey(samUser.id, GoogleProject(project), serviceAccountKeyId, samRequestContext)
                          .map(_ => StatusCodes.NoContent)
                      }
                    }
                  }
              } ~
                pathPrefix("token") {
                  requireOneOfActionIfParentIsWorkspace(
                    FullyQualifiedResourceId(SamResourceTypes.googleProjectName, projectResourceId),
                    Set(SamResourceActions.createPet),
                    samUser.id,
                    samRequestContext
                  ) {
                    post {
                      entity(as[Set[String]]) { scopes =>
                        complete {
                          googleExtensions.petServiceAccounts
                            .getPetServiceAccountToken(samUser, GoogleProject(project), scopes, samRequestContext)
                            .map { token =>
                              StatusCodes.OK -> JsString(token)
                            }
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("signedUrlForBlob") {
                  requireOneOfAction(
                    FullyQualifiedResourceId(SamResourceTypes.googleProjectName, projectResourceId),
                    Set(SamResourceActions.createPet),
                    samUser.id,
                    samRequestContext
                  ) {
                    post {
                      entity(as[SignedUrlRequest]) { request =>
                        complete {
                          googleExtensions
                            .getSignedUrl(
                              samUser,
                              GoogleProject(project),
                              GcsBucketName(request.bucketName),
                              GcsBlobName(request.blobName),
                              request.duration,
                              request.requesterPays.exists(identity),
                              samRequestContext
                            )
                            .map { signedUrl =>
                              StatusCodes.OK -> JsString(signedUrl.toString)
                            }
                        }
                      }
                    }
                  }
                } ~
                pathEnd {
                  requireOneOfActionIfParentIsWorkspace(
                    FullyQualifiedResourceId(SamResourceTypes.googleProjectName, projectResourceId),
                    Set(SamResourceActions.createPet),
                    samUser.id,
                    samRequestContext
                  ) {
                    get {
                      complete {
                        googleExtensions.petServiceAccounts.createUserPetServiceAccount(samUser, GoogleProject(project), samRequestContext).map { petSA =>
                          StatusCodes.OK -> petSA.serviceAccount.email
                        }
                      }
                    }
                  } ~
                    delete {
                      complete {
                        googleExtensions.deleteUserPetServiceAccount(samUser.id, GoogleProject(project), samRequestContext).map(_ => StatusCodes.NoContent)
                      }
                    }
                }
            }
        } ~
        pathPrefix("user") {
          pathPrefix("signedUrlForBlob") {
            post {
              entity(as[RequesterPaysSignedUrlRequest]) { request =>
                complete {
                  googleExtensions
                    .getRequesterPaysSignedUrl(
                      samUser,
                      request.gsPath,
                      request.duration,
                      request.requesterPaysProject.map(GoogleProject),
                      samRequestContext
                    )
                    .map { signedUrl =>
                      StatusCodes.OK -> JsString(signedUrl.toString)
                    }
                }
              }
            }
          } ~
            path("proxyGroup" / Segment) { targetUserEmail =>
              val workbenchEmail = WorkbenchEmail(targetUserEmail)
              get {
                complete {
                  googleExtensions.getUserProxy(workbenchEmail, samRequestContext).map {
                    case Some(proxyEmail) => StatusCodes.OK -> Option(proxyEmail)
                    case _ => StatusCodes.NotFound -> None
                  }
                }
              }
            }
        } ~
        pathPrefix("resource") {
          path(Segment / Segment / Segment / "sync") { (resourceTypeName, resourceId, accessPolicyName) =>
            val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
            val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(accessPolicyName))
            val params =
              Seq("resourceTypeName" -> resource.resourceTypeName, "resourceId" -> resource.resourceId, "accessPolicyName" -> policyId.accessPolicyName)
            pathEndOrSingleSlash {
              post {
                complete {
                  import SamGoogleModelJsonSupport._
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
    } ~
      pathPrefix("google" / "v2") {
        pathPrefix("actionServiceAccount") {
          path(Segment / Segment / Segment / Segment) { (project, resourceTypeName, resourceId, action) =>
            val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
            val googleProject = GoogleProject(project)
            val resourceAction = ResourceAction(action)

            withNonAdminResourceType(resource.resourceTypeName) { resourceType =>
              if (!resourceType.actionPatterns.map(ap => ResourceAction(ap.value)).contains(resourceAction)) {
                throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"action $action not found"))
              }
              pathEndOrSingleSlash {
                post {
                  complete {
                    googleExtensions.actionServiceAccounts.createActionServiceAccount(resource, googleProject, resourceAction, samRequestContext).map {
                      StatusCodes.OK -> _
                    }
                  }
                }
              } ~
                pathPrefix("signedUrlForBlob") {
                  pathEndOrSingleSlash {
                    post {
                      requireAction(resource, resourceAction, samUser.id, samRequestContext) {
                        entity(as[RequesterPaysSignedUrlRequest]) { request =>
                          complete {
                            googleExtensions
                              .getRequesterPaysSignedUrl(
                                samUser,
                                resource.resourceId,
                                resourceAction,
                                googleProject,
                                request.gsPath,
                                request.duration,
                                request.requesterPaysProject.map(GoogleProject),
                                samRequestContext
                              )
                              .map { signedUrl =>
                                StatusCodes.OK -> JsString(signedUrl.toString)
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
}
