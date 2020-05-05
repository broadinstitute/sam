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
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, DbReference}
import org.broadinstitute.dsde.workbench.sam.directory.PostgresDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.openam.PostgresAccessPolicyDAO
import org.broadinstitute.dsde.workbench.util.ExecutionContexts
import scalikejdbc.config.DBs
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.completeWithTrace
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

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
              completeWithTrace(_ => initializePostgresResourceTypes)
            }
          }
        }
      }
    } ~
    (pathPrefix("config" / "v1" / "resourceTypes") | pathPrefix("resourceTypes")) {
        requireUserInfo { userInfo =>
          pathEndOrSingleSlash {
            get {
              completeWithTrace(_ => resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet))
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
                  } ~ pathPrefix("userEmail") {
                    pathPrefix(Segment) { userEmail =>
                      pathEndOrSingleSlash {
                        getActionPermissionForUserEmail(resource, userInfo, ResourceAction(action), WorkbenchEmail(userEmail))
                      }
                    }
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
    val dbName = DatabaseNames.Foreground
    implicit val contextShift = IO.contextShift(ExecutionContext.Implicits.global)
    val postgresResourceService: cats.effect.Resource[IO, ResourceService] = for {
      postgresExecutionContext <- ExecutionContexts.fixedThreadPool[IO](DBs.config.getInt(s"db.${dbName.name.name}.poolMaxSize"))
      dbReference <- DbReference.resource(liquibaseConfig, dbName)

      postgresAccessPolicyDAO = new PostgresAccessPolicyDAO(dbReference, postgresExecutionContext)
      postgresDirectoryDAO = new PostgresDirectoryDAO(dbReference, postgresExecutionContext)
    } yield new ResourceService(resourceService.getResourceTypes().unsafeRunSync(), policyEvaluatorService, postgresAccessPolicyDAO, postgresDirectoryDAO, cloudExtensions, resourceService.emailDomain)

    postgresResourceService.use { resourceService =>
      resourceService.initResourceTypes()
    }
  }

  def getUserPoliciesForResourceType(resourceType: ResourceType, userInfo: UserInfo): server.Route =
    get {
      completeWithTrace(samRequestContext => policyEvaluatorService.listUserAccessPolicies(resourceType.name, userInfo.userId, samRequestContext))
    }

  def postResource(resourceType: ResourceType, userInfo: UserInfo): server.Route =
    post {
        entity(as[CreateResourceRequest]) { createResourceRequest =>
          if (resourceType.reuseIds && resourceType.isAuthDomainConstrainable) {
            throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "this api may not be used for resource types that allow both authorization domains and id reuse"))
          }

          def resourceMaker(samRequestContext: SamRequestContext): IO[ToResponseMarshallable] = resourceService
            .createResource(resourceType, createResourceRequest.resourceId, createResourceRequest.policies, createResourceRequest.authDomain, userInfo.userId, samRequestContext)
            .map { r =>
              if (createResourceRequest.returnResource.contains(true)) {
                StatusCodes.Created -> CreateResourceResponse(r.resourceTypeName, r.resourceId, r.authDomain, r.accessPolicies.map(ap => CreateResourcePolicyResponse(ap.id, ap.email)))
              }  else {
                StatusCodes.NoContent
              }
            }

          completeWithTrace(samRequestContext => resourceMaker(samRequestContext))
      }
    }

  def deleteResource(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    delete {
      requireAction(resource, SamResourceActions.delete, userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.deleteResource(resource, samRequestContext).map(_ => StatusCodes.NoContent))
      }
    }

  def postDefaultResource(resourceType: ResourceType, resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    post {
      completeWithTrace(samRequestContext => resourceService.createResource(resourceType, resource.resourceId, userInfo, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def getActionPermissionForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo, action: String): server.Route =
    get {
      completeWithTrace { samRequestContext =>
        policyEvaluatorService.hasPermission(resource, ResourceAction(action), userInfo.userId, samRequestContext).map { hasPermission =>
          StatusCodes.OK -> JsBoolean(hasPermission)
        }
      }
    }

  /**
    * Checks if user has permission by giver user email.
    *
    * <p> The caller should have readPolicies, OR testAnyActionAccess or testActionAccess::{action} to make this call.
    */
  def getActionPermissionForUserEmail(resource: FullyQualifiedResourceId, userInfo: UserInfo, action: ResourceAction, userEmail: WorkbenchEmail): server.Route =
    get {
      requireOneOfAction(resource, Set(SamResourceActions.readPolicies, SamResourceActions.testAnyActionAccess, SamResourceActions.testActionAccess(action)), userInfo.userId) {
        completeWithTrace{samRequestContext => policyEvaluatorService.hasPermissionByUserEmail(resource, action, userEmail, samRequestContext).map { hasPermission =>
            StatusCodes.OK -> JsBoolean(hasPermission)
          }
        }
      }
    }

  def listActionsForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      completeWithTrace(samRequestContext => policyEvaluatorService.listUserResourceActions(resource, userInfo.userId, samRequestContext = samRequestContext).map { actions =>
        StatusCodes.OK -> actions
      })
    }

  def getResourceAuthDomain(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      requireAction(resource, SamResourceActions.readAuthDomain, userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.loadResourceAuthDomain(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response
        })
      }
    }

  def getResourcePolicies(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      requireAction(resource, SamResourceActions.readPolicies, userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response.toSet
        })
      }
    }

  def getPolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    get {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.loadResourcePolicy(policyId, samRequestContext).map {
          case Some(response) => StatusCodes.OK -> response
          case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found"))
        })
      }
    }

  def putPolicyOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    put {
      requireAction(policyId.resource, SamResourceActions.alterPolicies, userInfo.userId) {
        entity(as[AccessPolicyMembership]) { membershipUpdate =>
          completeWithTrace(samRequestContext => resourceService.overwritePolicy(resourceType, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext).map(_ => StatusCodes.Created))
        }
      }
    }

  def putPolicyMembershipOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    put {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId) {
        entity(as[Set[WorkbenchEmail]]) { membersList =>
          completeWithTrace(samRequestContext =>
            resourceService.overwritePolicyMembers(resourceType, policyId.accessPolicyName, policyId.resource, membersList, samRequestContext).map(_ => StatusCodes.NoContent))
        }
      }
    }

  def putUserInPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject): server.Route =
    put {
      completeWithTrace(samRequestContext => resourceService.addSubjectToPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def deleteUserFromPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject): server.Route =
    delete {
      completeWithTrace(samRequestContext => resourceService.removeSubjectFromPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def getPublicFlag(policyId: FullyQualifiedPolicyId, userInfo: UserInfo): server.Route =
    get {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.isPublic(policyId, samRequestContext))
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
            completeWithTrace(samRequestContext => resourceService.setPublic(policyId, isPublic, samRequestContext).map(_ => StatusCodes.NoContent))
          }
        }
      }
    }

  def getUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      completeWithTrace { samRequestContext =>
        resourceService.listUserResourceRoles(resource, userInfo, samRequestContext).map { roles =>
          StatusCodes.OK -> roles
        }
      }
    }

  def getAllResourceUsers(resource: FullyQualifiedResourceId, userInfo: UserInfo): server.Route =
    get {
      requireAction(resource, SamResourceActions.readPolicies, userInfo.userId) {
        completeWithTrace(samRequestContext => resourceService.listAllFlattenedResourceUsers(resource, samRequestContext).map { allUsers =>
          StatusCodes.OK -> allUsers
        })
      }
    }
}
