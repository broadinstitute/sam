package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.{CreateResourcePolicyResponse, CreateResourceResponse, _}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext
import ImplicitConversions.ioOnSuccessMagnet
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.directory.PostgresDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.openam.PostgresAccessPolicyDAO
import org.broadinstitute.dsde.workbench.util.ExecutionContexts
import scalikejdbc.config.DBs
import io.opencensus.scala.akka.http.TracingDirective._

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives with SecurityDirectives with SamModelDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def withResourceType(name: ResourceTypeName): Directive1[ResourceType] =
    onSuccess(resourceService.getResourceType(name)).map {
      case Some(resourceType) => resourceType
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type ${name.value} not found"))
    }

  def resourceRoutes: server.Route =
    pathPrefix("initializeResourceTypes") {
      requireUserInfo { userInfo =>
        asWorkbenchAdmin(userInfo) {
          pathEndOrSingleSlash {
            put {
              complete {
                initializePostgresResourceTypes
              }
            }
          }
        }
      }
    } ~
    (pathPrefix("config" / "v1" / "resourceTypes") | pathPrefix("resourceTypes")) {
        requireUserInfo { userInfo =>
          pathEndOrSingleSlash {
            get {
              complete {
                resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet)
              }
            }
          }
        }
    } ~
    (pathPrefix("resources" / "v1") | pathPrefix("resource")) {
      requireUserInfo { userInfo =>
        pathPrefix(Segment) { resourceTypeName =>
          withResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
            pathEndOrSingleSlash {
              getUserPoliciesForResourceType(resourceType, userInfo) ~
                postResource(resourceType, userInfo)
            } ~ pathPrefix(Segment) { resourceId =>
              val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))

              pathEndOrSingleSlash {
                deleteResource(resource, userInfo) ~
                  postDefaultResource(resourceType, resource, userInfo)
              } ~ pathPrefix("action") {
                pathPrefix(Segment) { action =>
                  pathEndOrSingleSlash {
                    getActionPermissionForUser(resource, userInfo, action)
                  }
                }
              } ~ pathPrefix("authDomain") {
                pathEndOrSingleSlash {
                  getResourceAuthDomain(resource, userInfo)
                }
              } ~ pathPrefix("policies") {
                pathEndOrSingleSlash {
                  getResourcePolicies(resource, userInfo)
                } ~ pathPrefix(Segment) { policyName =>
                  val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                  pathEndOrSingleSlash {
                    getPolicy(policyId, userInfo) ~
                      putPolicyOverwrite(resourceType, policyId, userInfo)
                  } ~ pathPrefix("memberEmails") {
                    pathEndOrSingleSlash {
                      putPolicyMembershipOverwrite(resourceType, policyId, userInfo)
                    } ~ pathPrefix(Segment) { email =>
                      withSubject(WorkbenchEmail(email)) { subject =>
                        pathEndOrSingleSlash {
                          requireOneOfAction(
                            resource,
                            Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
                            userInfo.userId) {
                            putUserInPolicy(policyId, subject) ~
                              deleteUserFromPolicy(policyId, subject)
                          }
                        }
                      }
                    }
                  } ~ pathPrefix("public") {
                    pathEndOrSingleSlash {
                      getPublicFlag(policyId, userInfo) ~
                        putPublicFlag(policyId, userInfo)
                    }
                  }
                }
              } ~ pathPrefix("roles") {
                pathEndOrSingleSlash {
                  getUserResourceRoles(resource, userInfo)
                }
              } ~ pathPrefix("actions") {
                pathEndOrSingleSlash {
                  listActionsForUser(resource, userInfo)
                }
              } ~ pathPrefix("allUsers") {
                pathEndOrSingleSlash {
                  getAllResourceUsers(resource, userInfo)
                }
              }
            }
          }
        }
      }
    }

  private def initializePostgresResourceTypes = {
    val dbName: Symbol = 'sam_foreground
    implicit val contextShift = IO.contextShift(ExecutionContext.Implicits.global)
    val postgresResourceService: cats.effect.Resource[IO, ResourceService] = for {
      postgresExecutionContext <- ExecutionContexts.fixedThreadPool[IO](DBs.config.getInt(s"db.${dbName.name}.poolMaxSize"))
      dbReference <- DbReference.resource(liquibaseConfig, dbName)

      postgresAccessPolicyDAO = new PostgresAccessPolicyDAO(dbReference, postgresExecutionContext)
      postgresDirectoryDAO = new PostgresDirectoryDAO(dbReference, postgresExecutionContext)
    } yield new ResourceService(resourceService.getResourceTypes().unsafeRunSync(), policyEvaluatorService, postgresAccessPolicyDAO, postgresDirectoryDAO, cloudExtensions, resourceService.emailDomain)

    postgresResourceService.use { resourceService =>
      resourceService.initResourceTypes()
    }
  }

  def getUserPoliciesForResourceType(resourceType: ResourceType, userInfo: UserInfo): server.Route =
    traceRequest { span =>
      get {
        complete(policyEvaluatorService.listUserAccessPolicies(resourceType.name, userInfo.userId))
      }
    }

  def postResource(resourceType: ResourceType, userInfo: UserInfo): server.Route =
    post {
        entity(as[CreateResourceRequest]) { createResourceRequest =>
          if (resourceType.reuseIds && resourceType.isAuthDomainConstrainable) {
            throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "this api may not be used for resource types that allow both authorization domains and id reuse"))
          }

          val resourceMaker: IO[ToResponseMarshallable] = resourceService
            .createResource(resourceType, createResourceRequest.resourceId, createResourceRequest.policies, createResourceRequest.authDomain, userInfo.userId)
            .map { r =>
              if (createResourceRequest.returnResource.contains(true)) {
                StatusCodes.Created -> CreateResourceResponse(r.resourceTypeName, r.resourceId, r.authDomain, r.accessPolicies.map(ap => CreateResourcePolicyResponse(ap.id, ap.email)))
              }  else {
                StatusCodes.NoContent
              }
            }

          complete(resourceMaker)
      }
    }

  def deleteResource(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    delete {
      requireAction(resource, SamResourceActions.delete, userInfo.userId) {
        complete(resourceService.deleteResource(resource).map(_ => StatusCodes.NoContent))
      }
    }

  def postDefaultResource(resourceType: ResourceType, resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    post {
      complete(resourceService.createResource(resourceType, resource.resourceId, userInfo).map(_ => StatusCodes.NoContent))
    }

  def getActionPermissionForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo, action: String): server.Route =
    get {
      traceRequest { span =>
        complete(policyEvaluatorService.hasPermission(resource, ResourceAction(action), userInfo.userId, span).map { hasPermission =>
          StatusCodes.OK -> JsBoolean(hasPermission)
        })
      }
    }

  def listActionsForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      complete(policyEvaluatorService.listUserResourceActions(resource, userInfo.userId).map { actions =>
        StatusCodes.OK -> actions
      })
    }

  def getResourceAuthDomain(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      requireAction(resource, SamResourceActions.readAuthDomain, userInfo.userId) {
        complete(resourceService.loadResourceAuthDomain(resource).map { response =>
          StatusCodes.OK -> response
        })
      }
    }

  def getResourcePolicies(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    traceRequest { span =>
      get {
        requireAction(resource, SamResourceActions.readPolicies, userInfo.userId) {
          complete(resourceService.listResourcePolicies(resource).map { response =>
            StatusCodes.OK -> response.toSet
          })
        }
      }
    }

  def getPolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    traceRequest { span =>
      get {
        requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId) {
          complete(resourceService.loadResourcePolicy(policyId).map {
            case Some(response) => StatusCodes.OK -> response
            case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found"))
          })
        }
      }
    }

  def putPolicyOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    put {
      requireAction(policyId.resource, SamResourceActions.alterPolicies, userInfo.userId) {
        entity(as[AccessPolicyMembership]) { membershipUpdate =>
          complete(resourceService.overwritePolicy(resourceType, policyId.accessPolicyName, policyId.resource, membershipUpdate).map(_ => StatusCodes.Created))
        }
      }
    }

  def putPolicyMembershipOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    put {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId) {
        entity(as[Set[WorkbenchEmail]]) { membersList =>
          complete(
            resourceService.overwritePolicyMembers(resourceType, policyId.accessPolicyName, policyId.resource, membersList).map(_ => StatusCodes.NoContent))
        }
      }
    }

  def putUserInPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject): server.Route =
    put {
      complete(resourceService.addSubjectToPolicy(policyId, subject).map(_ => StatusCodes.NoContent))
    }

  def deleteUserFromPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject): server.Route =
    delete {
      complete(resourceService.removeSubjectFromPolicy(policyId, subject).map(_ => StatusCodes.NoContent))
    }

  def getPublicFlag(policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    get {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId) {
        complete(resourceService.isPublic(policyId))
      }
    }

  def putPublicFlag(policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    put {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId) {
        requireOneOfAction(
          FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(policyId.resource.resourceTypeName.value)),
          Set(SamResourceActions.setPublic, SamResourceActions.setPublicPolicy(policyId.accessPolicyName)),
          userInfo.userId
        ) {
          entity(as[Boolean]) { isPublic =>
            complete(resourceService.setPublic(policyId, isPublic).map(_ => StatusCodes.NoContent))
          }
        }
      }
    }

  def getUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      traceRequest { span =>
        complete(resourceService.listUserResourceRoles(resource, userInfo).map { roles =>
          StatusCodes.OK -> roles
        })
      }
    }

  def getAllResourceUsers(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      requireAction(resource, SamResourceActions.readPolicies, userInfo.userId) {
        complete(resourceService.listAllFlattenedResourceUsers(resource).map { allUsers =>
          StatusCodes.OK -> allUsers
        })
      }
    }
}
