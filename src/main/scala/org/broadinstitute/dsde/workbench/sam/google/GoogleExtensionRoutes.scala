package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import cats.effect.IO
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.google2.GcsBlobName
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ValueObject, WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.api.{
  ExtensionRoutes,
  SamModelDirectives,
  SamRequestContextDirectives,
  SamUserDirectives,
  SecurityDirectives,
  ioMarshaller
}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AllUsersCleanupRecord, ExternalMembersMigrationRecord}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import spray.json.JsString

import scala.concurrent.ExecutionContext

trait GoogleExtensionRoutes extends ExtensionRoutes with SamUserDirectives with SecurityDirectives with SamModelDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val googleExtensions: GoogleExtensions
  val googleGroupSynchronizer: GoogleGroupSynchronizer
  val groupExternalMembersMigrator: GoogleGroupExternalMembersMigrator
  val allUsersGroupCleanupMigrator: AllUsersGroupCleanupMigrator

  private def formatMigrationStatus(records: Seq[ExternalMembersMigrationRecord]): String =
    if (records.isEmpty) "no allowExternalMembers migrations have been started"
    else
      records
        .map { r =>
          val total = r.total.map(_.toString).getOrElse("?")
          s"${r.tier}: ${r.state}  ${r.processed}/$total (failed ${r.failed})  cursor=${r.lastCursor.getOrElse("-")}  updated=${r.updatedAt}"
        }
        .mkString("\n")

  private def formatCleanupStatus(records: Seq[AllUsersCleanupRecord]): String =
    if (records.isEmpty) "no All_Users cleanup runs have been started"
    else
      records
        .map { r =>
          val total = r.total.map(_.toString).getOrElse("?")
          s"${r.emailPattern}: ${r.state}  ${r.processed}/$total (failed ${r.failed})  cursor=${r.lastCursor.getOrElse("-")}  updated=${r.updatedAt}"
        }
        .mkString("\n")

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
          pathPrefix(Segment) { userEmail =>
            val email = WorkbenchEmail(userEmail)
            path("key") {
              getWithTelemetry(samRequestContext, "userEmail" -> email) {
                complete {
                  import spray.json._
                  googleExtensions.getArbitraryPetServiceAccountKey(email, samRequestContext) map {
                    // parse json to ensure it is json and tells akka http the right content-type
                    case Some(key) => StatusCodes.OK -> key.parseJson
                    case None =>
                      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                  }
                }
              }
            } ~
              path("token") {
                postWithTelemetry(samRequestContext, "userEmail" -> email) {
                  entity(as[Set[String]]) { scopes =>
                    complete {
                      googleExtensions.getArbitraryPetServiceAccountToken(email, scopes, samRequestContext).map {
                        case Some(token) => StatusCodes.OK -> JsString(token)
                        case None =>
                          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                      }
                    }
                  }
                }
              }
          } ~
            pathPrefix(Segment / Segment) { (project, userEmail) =>
              val email = WorkbenchEmail(userEmail)
              val googleProject = GoogleProject(project)
              pathEndOrSingleSlash {
                getWithTelemetry(samRequestContext, "userEmail" -> email, "googleProject" -> googleProject) {
                  complete {
                    import spray.json._
                    googleExtensions.getPetServiceAccountKey(email, googleProject, samRequestContext) map {
                      // parse json to ensure it is json and tells akka http the right content-type
                      case Some(key) => StatusCodes.OK -> key.parseJson
                      case None =>
                        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                    }
                  }
                }
              } ~
                path("token") {
                  postWithTelemetry(samRequestContext, "userEmail" -> email) {
                    entity(as[Set[String]]) { scopes =>
                      complete {
                        googleExtensions.getPetServiceAccountToken(email, googleProject, scopes, samRequestContext).map {
                          case Some(token) => StatusCodes.OK -> JsString(token)
                          case None =>
                            throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "pet service account not found"))
                        }
                      }
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
              getWithTelemetry(samRequestContext) {
                complete {
                  import spray.json._
                  googleExtensions
                    .getArbitraryPetServiceAccountKey(samUser, samRequestContext)
                    .map(key => StatusCodes.OK -> key.parseJson)
                }
              }
            }
          } ~
            pathPrefix("token") {
              pathEndOrSingleSlash {
                postWithTelemetry(samRequestContext) {
                  entity(as[Set[String]]) { scopes =>
                    complete {
                      googleExtensions.getArbitraryPetServiceAccountToken(samUser, scopes, samRequestContext).map { token =>
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
                  getWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
                    complete {
                      import spray.json._
                      // parse json to ensure it is json and tells akka http the right content-type
                      googleExtensions
                        .getPetServiceAccountKey(samUser, GoogleProject(project), samRequestContext)
                        .map { key =>
                          StatusCodes.OK -> key.parseJson
                        }
                    }
                  }
                } ~
                  path(Segment) { keyId =>
                    val serviceAccountKeyId = ServiceAccountKeyId(keyId)
                    deleteWithTelemetry(samRequestContext, "googleProject" -> projectResourceId, "keyId" -> serviceAccountKeyId) {
                      complete {
                        googleExtensions
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
                    postWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
                      entity(as[Set[String]]) { scopes =>
                        complete {
                          googleExtensions
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
                    postWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
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
                    getWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
                      complete {
                        googleExtensions.createUserPetServiceAccount(samUser, GoogleProject(project), samRequestContext).map { petSA =>
                          StatusCodes.OK -> petSA.serviceAccount.email
                        }
                      }
                    }
                  } ~
                    deleteWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
                      complete {
                        googleExtensions.deleteUserPetServiceAccount(samUser.id, GoogleProject(project), samRequestContext).map(_ => StatusCodes.NoContent)
                      }
                    }
                }
            }
        } ~
        pathPrefix("user") {
          pathPrefix("signedUrlForBlob") {
            postWithTelemetry(samRequestContext) {
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
              getWithTelemetry(samRequestContext, "email" -> workbenchEmail) {
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
              postWithTelemetry(samRequestContext, params: _*) {
                complete {
                  import SamGoogleModelJsonSupport._
                  googleGroupSynchronizer.synchronizeGroupMembers(policyId, samRequestContext = samRequestContext).map { syncReport =>
                    StatusCodes.OK -> syncReport
                  }
                }
              } ~
                getWithTelemetry(samRequestContext, params: _*) {
                  complete {
                    googleExtensions.getSynchronizedState(policyId, samRequestContext).map {
                      case Some(syncState) => StatusCodes.OK -> Option(syncState)
                      case None => StatusCodes.NoContent -> None
                    }
                  }
                }
            }
          }
        } ~
        pathPrefix("groups") {
          // Super-admin-only one-off migration: ensure allowExternalMembers is enabled on existing Google groups.
          asSamSuperAdmin(samUser) {
            pathPrefix("allowExternalMembers" / "migrate") {
              // GET .../migrate/status -> plain-text progress report, one line per tier that has been started
              path("status") {
                getWithTelemetry(samRequestContext) {
                  complete {
                    groupExternalMembersMigrator.status(samRequestContext).map(formatMigrationStatus)
                  }
                }
              } ~
                // PUT .../migrate/{tier[,tier...]} -> run the given priority tiers ("proxy" or resource type names) in order, in the background.
                // Returns immediately; safe to re-run to resume (completed tiers are skipped, a crashed tier resumes from its last cursor).
                // Optional queriesPerMinute query param sets the Groups Settings API rate limit (the single throughput control) for this run.
                path(Segment) { tierSelector =>
                  parameters("queriesPerMinute".as[Int].optional) { queriesPerMinute =>
                    putWithTelemetry(samRequestContext, "tiers" -> new ValueObject { val value: String = tierSelector }) {
                      complete {
                        val validResourceTypes = googleExtensions.resourceTypes.keySet.map(_.value)
                        val (unknown, tiers) = tierSelector
                          .split(",")
                          .toList
                          .map(_.trim)
                          .filter(_.nonEmpty)
                          .partitionMap(s => MigrationTier.fromSelector(s, validResourceTypes).toRight(s))
                        if (unknown.nonEmpty)
                          IO.pure[(StatusCode, String)](
                            StatusCodes.BadRequest -> s"Unknown migration tier(s): ${unknown.mkString(", ")}. Valid tiers: proxy or a resource type name."
                          )
                        else if (queriesPerMinute.exists(_ < 1))
                          IO.pure[(StatusCode, String)](StatusCodes.BadRequest -> s"queriesPerMinute must be >= 1 (was ${queriesPerMinute.get})")
                        else
                          groupExternalMembersMigrator
                            .migrate(tiers, samRequestContext, queriesPerMinute)
                            .start
                            .as(StatusCodes.Accepted -> s"Started allowExternalMembers migration for tiers ${tiers.map(_.value).mkString(", ")}")
                      }
                    }
                  }
                }
            } ~
              // Super-admin-only one-off cleanup: remove users matching an email pattern from the All_Users group.
              pathPrefix("allUsers" / "cleanup") {
                // GET .../allUsers/cleanup/preview?emailPattern=X -> synchronous count of users currently matching the pattern, no Google calls.
                path("preview") {
                  parameter("emailPattern") { emailPattern =>
                    getWithTelemetry(samRequestContext, "emailPattern" -> new ValueObject { val value: String = emailPattern }) {
                      complete {
                        allUsersGroupCleanupMigrator.previewCount(emailPattern, samRequestContext).map(count => StatusCodes.OK -> count.toString)
                      }
                    }
                  }
                } ~
                  // GET .../allUsers/cleanup/status -> plain-text progress report, one line per email pattern that has been run
                  path("status") {
                    getWithTelemetry(samRequestContext) {
                      complete {
                        allUsersGroupCleanupMigrator.status(samRequestContext).map(formatCleanupStatus)
                      }
                    }
                  } ~
                  // PUT .../allUsers/cleanup?emailPattern=X -> remove every user matching the pattern from All_Users, in the background. Returns
                  // immediately; safe to re-run to resume (a completed pattern re-run starts fresh, a running/failed one resumes from its last cursor).
                  // Optional queriesPerMinute query param sets the Directory API rate limit (the single throughput control) for this run.
                  pathEndOrSingleSlash {
                    parameters("emailPattern", "queriesPerMinute".as[Int].optional) { (emailPattern, queriesPerMinute) =>
                      putWithTelemetry(samRequestContext, "emailPattern" -> new ValueObject { val value: String = emailPattern }) {
                        complete {
                          if (queriesPerMinute.exists(_ < 1))
                            IO.pure[(StatusCode, String)](StatusCodes.BadRequest -> s"queriesPerMinute must be >= 1 (was ${queriesPerMinute.get})")
                          else
                            allUsersGroupCleanupMigrator
                              .removeMatching(emailPattern, samRequestContext, queriesPerMinute)
                              .start
                              .as(StatusCodes.Accepted -> s"Started All_Users cleanup for pattern $emailPattern")
                        }
                      }
                    }
                  }
              }
          }
        } ~
        pathPrefix("project") {
          path(Segment) { project =>
            val projectResourceId = ResourceId(project)
            pathEndOrSingleSlash {
              requireAction(
                FullyQualifiedResourceId(SamResourceTypes.googleProjectName, projectResourceId),
                SamResourceActions.delete,
                samUser.id,
                samRequestContext
              ) {
                deleteWithTelemetry(samRequestContext, "googleProject" -> projectResourceId) {
                  complete {
                    googleExtensions
                      .forgetProject(GoogleProject(project), resourceService, samRequestContext)
                      .map(response => StatusCodes.OK -> response)
                  }
                }
              }
            }
          }
        }
    }
}
