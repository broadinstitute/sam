package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.ImplicitConversions.ioOnSuccessMagnet
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.{CreateResourcePolicyResponse, CreateResourceResponse, _}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives with SecurityDirectives with SamModelDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def withResourceType(name: ResourceTypeName): Directive1[ResourceType] =
    onSuccess(resourceService.getResourceType(name)).map {
      case Some(resourceType) => resourceType
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type ${name.value} not found"))
    }

  def adminResourceRotues: server.Route =
    pathPrefix("resourceTypeAdmin" / "v1") {
      pathPrefix("resourceTypes") {
        withSamRequestContext { samRequestContext =>
          requireUserInfo(samRequestContext) { userInfo =>
            asSamSuperAdmin(userInfo) {
              pathPrefix(Segment) { resourceTypeNameToAdminister =>
                withResourceType(SamResourceTypes.resourceTypeAdminName) { resourceType =>
                  pathPrefix("policies") {
                    // TODO: CA-1248 GET api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies
//                    pathEndOrSingleSlash{
//
//                    }
                    pathPrefix(Segment) { policyName =>
                      val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(resourceTypeNameToAdminister)), AccessPolicyName(policyName))
                      pathEndOrSingleSlash {
                        put {
                          entity(as[AccessPolicyMembership]) { membershipUpdate =>
                            complete(resourceService.overwritePolicy(resourceType, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext).map(_ => StatusCodes.Created))
                          }
                        } ~
                        delete {
                          complete(resourceService.deletePolicy(policyId, samRequestContext).map(_ => StatusCodes.NoContent))
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

  def resourceRoutes: server.Route =
    (pathPrefix("config" / "v1" / "resourceTypes") | pathPrefix("resourceTypes")) {
        requireUserInfo(SamRequestContext(None)) { userInfo => // `SamRequestContext(None)` is used so that we don't trace 1-off boot/init methods ; these in particular are unpublished APIs
          pathEndOrSingleSlash {
            get {
              complete(resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet))
            }
          }
        }
    } ~
    (pathPrefix("resources" / "v1") | pathPrefix("resource")) {
      withSamRequestContext { samRequestContext =>
        requireUserInfo(samRequestContext) { userInfo =>
          pathPrefix(Segment) { resourceTypeName =>
            withResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
              pathEndOrSingleSlash {
                getUserPoliciesForResourceType(resourceType, userInfo, samRequestContext) ~
                  postResource(resourceType, userInfo, samRequestContext)
              } ~ pathPrefix(Segment) { resourceId =>
                val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))

                pathEndOrSingleSlash {
                  deleteResource(resource, userInfo, samRequestContext) ~
                    postDefaultResource(resourceType, resource, userInfo, samRequestContext)
                } ~ pathPrefix("action") {
                  pathPrefix(Segment) { action =>
                    pathEndOrSingleSlash {
                      getActionPermissionForUser(resource, userInfo, action, samRequestContext)
                    } ~ pathPrefix("userEmail") {
                      pathPrefix(Segment) { userEmail =>
                        pathEndOrSingleSlash {
                          getActionPermissionForUserEmail(resource, userInfo, ResourceAction(action), WorkbenchEmail(userEmail), samRequestContext)
                        }
                      }
                    }
                  }
                } ~ pathPrefix("authDomain") {
                  pathEndOrSingleSlash {
                    getResourceAuthDomain(resource, userInfo, samRequestContext)
                  }
                } ~ pathPrefix("policies") {
                  pathEndOrSingleSlash {
                    getResourcePolicies(resource, userInfo, samRequestContext)
                  } ~ pathPrefix(Segment) { policyName =>
                    val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                    pathEndOrSingleSlash {
                      getPolicy(policyId, userInfo, samRequestContext) ~
                        putPolicyOverwrite(resourceType, policyId, userInfo, samRequestContext)
                    } ~ pathPrefix("memberEmails") {
                      pathEndOrSingleSlash {
                        putPolicyMembershipOverwrite(resourceType, policyId, userInfo, samRequestContext)
                      } ~ pathPrefix(Segment) { email =>
                        withSubject(WorkbenchEmail(email), samRequestContext) { subject =>
                          pathEndOrSingleSlash {
                            requireOneOfAction(
                              resource,
                              Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
                              userInfo.userId,
                              samRequestContext) {
                              putUserInPolicy(policyId, subject, samRequestContext) ~
                                deleteUserFromPolicy(policyId, subject, samRequestContext)
                            }
                          }
                        }
                      }
                    } ~ pathPrefix("public") {
                      pathEndOrSingleSlash {
                        getPublicFlag(policyId, userInfo, samRequestContext) ~
                          putPublicFlag(policyId, userInfo, samRequestContext)
                      }
                    }
                  }
                } ~ pathPrefix("roles") {
                  pathEndOrSingleSlash {
                    getUserResourceRoles(resource, userInfo, samRequestContext)
                  }
                } ~ pathPrefix("actions") {
                  pathEndOrSingleSlash {
                    listActionsForUser(resource, userInfo, samRequestContext)
                  }
                } ~ pathPrefix("allUsers") {
                  pathEndOrSingleSlash {
                    getAllResourceUsers(resource, userInfo, samRequestContext)
                  }
                }
              }
            }
          }
        }
      }
    } ~ pathPrefix("resources" / "v2") {
      withSamRequestContext { samRequestContext =>
        requireUserInfo(samRequestContext) { userInfo =>
          pathPrefix(Segment) { resourceTypeName =>
            withResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
              pathEndOrSingleSlash {
                getUserResourcesOfType(resourceType, userInfo, samRequestContext) ~
                postResource(resourceType, userInfo, samRequestContext)
              } ~
              pathPrefix(Segment) { resourceId =>
                val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))

                pathEndOrSingleSlash {
                  deleteResource(resource, userInfo, samRequestContext) ~
                  postDefaultResource(resourceType, resource, userInfo, samRequestContext)
                } ~
                pathPrefix("action") {
                  pathPrefix(Segment) { action =>
                    pathEndOrSingleSlash {
                      getActionPermissionForUser(resource, userInfo, action, samRequestContext)
                    } ~
                    pathPrefix("userEmail") {
                      pathPrefix(Segment) { userEmail =>
                        pathEndOrSingleSlash {
                          getActionPermissionForUserEmail(resource, userInfo, ResourceAction(action), WorkbenchEmail(userEmail), samRequestContext)
                        }
                      }
                    }
                  }
                } ~
                pathPrefix("authDomain") {
                  pathEndOrSingleSlash {
                    getResourceAuthDomain(resource, userInfo, samRequestContext)
                  }
                } ~
                pathPrefix("roles") {
                    pathEndOrSingleSlash {
                      getUserResourceRoles(resource, userInfo, samRequestContext)
                    }
                } ~
                pathPrefix("actions") {
                  pathEndOrSingleSlash {
                    listActionsForUser(resource, userInfo, samRequestContext)
                  }
                } ~
                pathPrefix("allUsers") {
                  pathEndOrSingleSlash {
                    getAllResourceUsers(resource, userInfo, samRequestContext)
                  }
                } ~
                pathPrefix("parent") {
                  pathEndOrSingleSlash {
                    getResourceParent(resource, userInfo, samRequestContext) ~
                    setResourceParent(resource, userInfo, samRequestContext) ~
                    deleteResourceParent(resource, userInfo, samRequestContext)
                  }
                } ~
                pathPrefix("children") {
                  pathEndOrSingleSlash {
                    getResourceChildren(resource, userInfo, samRequestContext)
                  }
                } ~
                pathPrefix ("policies") {
                  pathEndOrSingleSlash {
                    getResourcePolicies(resource, userInfo, samRequestContext)
                  } ~ pathPrefix(Segment) { policyName =>
                    val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                    pathEndOrSingleSlash {
                      getPolicy(policyId, userInfo, samRequestContext) ~
                        putPolicyOverwrite(resourceType, policyId, userInfo, samRequestContext) ~
                        deletePolicy(policyId, userInfo, samRequestContext)
                    } ~
                    pathPrefix("memberEmails") {
                      requireActionsForSharePolicy(policyId, userInfo, samRequestContext) {
                        pathEndOrSingleSlash {
                          putPolicyMembershipOverwrite(resourceType, policyId, userInfo, samRequestContext)
                        } ~
                        pathPrefix(Segment) { email =>
                          withSubject(WorkbenchEmail(email), samRequestContext) { subject =>
                            pathEndOrSingleSlash {
                              putUserInPolicy(policyId, subject, samRequestContext) ~
                              deleteUserFromPolicy(policyId, subject, samRequestContext)
                            }
                          }
                        }
                      }
                    } ~
                    pathPrefix("public") {
                      pathEndOrSingleSlash {
                        getPublicFlag(policyId, userInfo, samRequestContext) ~
                        putPublicFlag(policyId, userInfo, samRequestContext)
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

  // this object supresses the deprecation warning on listUserAccessPolicies
  // see https://github.com/scala/bug/issues/7934
  object Deprecated { @deprecated("remove as part of CA-1031", "") class Corral { def listUserAccessPolicies = policyEvaluatorService.listUserAccessPolicies _ }; object Corral extends Corral }

  def getUserPoliciesForResourceType(resourceType: ResourceType, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      complete(Deprecated.Corral.listUserAccessPolicies(resourceType.name, userInfo.userId, samRequestContext))
    }

  def getUserResourcesOfType(resourceType: ResourceType, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      complete(policyEvaluatorService.listUserResources(resourceType.name, userInfo.userId, samRequestContext))
    }

  def postResource(resourceType: ResourceType, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    post {
      entity(as[CreateResourceRequest]) { createResourceRequest =>
        requireCreateWithOptionalParent(createResourceRequest.parent, resourceType, userInfo.userId, samRequestContext) {
          if (resourceType.reuseIds && resourceType.isAuthDomainConstrainable) {
            throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "this api may not be used for resource types that allow both authorization domains and id reuse"))
          }

          def resourceMaker(samRequestContext: SamRequestContext): IO[ToResponseMarshallable] = resourceService
            .createResource(resourceType, createResourceRequest.resourceId, createResourceRequest.policies, createResourceRequest.authDomain, createResourceRequest.parent, userInfo.userId, samRequestContext)
            .map { r =>
              if (createResourceRequest.returnResource.contains(true)) {
                StatusCodes.Created -> CreateResourceResponse(r.resourceTypeName, r.resourceId, r.authDomain, r.accessPolicies.map(ap => CreateResourcePolicyResponse(ap.id, ap.email)))
              } else {
                StatusCodes.NoContent
              }
            }

          complete(resourceMaker(samRequestContext))
        }
      }
    }

  def deleteResource(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    delete {
      // Note that this does not require remove_child on the parent if it exists. remove_child is meant to prevent
      // users from removing a child only to add it to a different parent and thus circumvent any permissions
      // a parent may be enforcing. Deleting a child does not allow this situation.
      requireAction(resource, SamResourceActions.delete, userInfo.userId, samRequestContext) {
        complete(resourceService.deleteResource(resource, samRequestContext).map(_ => StatusCodes.NoContent))
      }
    }

  def postDefaultResource(resourceType: ResourceType, resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    post {
      complete(resourceService.createResource(resourceType, resource.resourceId, userInfo, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def getActionPermissionForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo, action: String, samRequestContext: SamRequestContext): server.Route =
    get {
      complete {
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
  def getActionPermissionForUserEmail(resource: FullyQualifiedResourceId, userInfo: UserInfo, action: ResourceAction, userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): server.Route =
    get {
      requireOneOfAction(resource, Set(SamResourceActions.readPolicies, SamResourceActions.testAnyActionAccess, SamResourceActions.testActionAccess(action)), userInfo.userId, samRequestContext) {
        complete {
          policyEvaluatorService.hasPermissionByUserEmail(resource, action, userEmail, samRequestContext).map { hasPermission =>
            StatusCodes.OK -> JsBoolean(hasPermission)
          }
        }
      }
    }

  def listActionsForUser(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      complete(policyEvaluatorService.listUserResourceActions(resource, userInfo.userId, samRequestContext = samRequestContext).map { actions =>
        StatusCodes.OK -> actions
      })
    }

  def getResourceAuthDomain(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.readAuthDomain, userInfo.userId, samRequestContext) {
        complete(resourceService.loadResourceAuthDomain(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response
        })
      }
    }

  def getResourcePolicies(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.readPolicies, userInfo.userId, samRequestContext) {
        complete(resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response.toSet
        })
      }
    }

  def getPolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
        complete(resourceService.loadResourcePolicy(policyId, samRequestContext).map {
          case Some(response) => StatusCodes.OK -> response
          case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found"))
        })
      }
    }

  def putPolicyOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    put {
      requireAction(policyId.resource, SamResourceActions.alterPolicies, userInfo.userId, samRequestContext) {
        entity(as[AccessPolicyMembership]) { membershipUpdate =>
          complete(resourceService.overwritePolicy(resourceType, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext).map(_ => StatusCodes.Created))
        }
      }
    }

  private def requireActionsForSharePolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext)(sharePolicy: server.Route): server.Route =
    requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
      sharePolicy
    }

  def putPolicyMembershipOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    put {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
        entity(as[Set[WorkbenchEmail]]) { membersList =>
          complete(
            resourceService.overwritePolicyMembers(resourceType, policyId.accessPolicyName, policyId.resource, membersList, samRequestContext).map(_ => StatusCodes.NoContent))
        }
      }
    }

  def putUserInPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): server.Route =
    put {
      complete(resourceService.addSubjectToPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def deleteUserFromPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): server.Route =
    delete {
      complete(resourceService.removeSubjectFromPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def getPublicFlag(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
        complete(resourceService.isPublic(policyId, samRequestContext))
      }
    }

  def putPublicFlag(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    put {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
        requireOneOfAction(
          FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(policyId.resource.resourceTypeName.value)),
          Set(SamResourceActions.setPublic, SamResourceActions.setPublicPolicy(policyId.accessPolicyName)),
          userInfo.userId,
          samRequestContext
        ) {
          entity(as[Boolean]) { isPublic =>
            complete(resourceService.setPublic(policyId, isPublic, samRequestContext).map(_ => StatusCodes.NoContent))
          }
        }
      }
    }

  def getUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      complete {
        resourceService.listUserResourceRoles(resource, userInfo, samRequestContext).map { roles =>
          StatusCodes.OK -> roles
        }
      }
    }

  def getAllResourceUsers(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.readPolicies, userInfo.userId, samRequestContext) {
        complete(resourceService.listAllFlattenedResourceUsers(resource, samRequestContext).map { allUsers =>
          StatusCodes.OK -> allUsers
        })
      }
    }

  def getResourceParent(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.getParent, userInfo.userId, samRequestContext) {
        complete(resourceService.getResourceParent(resource, samRequestContext).map {
          case Some(response) => StatusCodes.OK -> response
          case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "resource parent not found"))
        })
      }
    }

  def setResourceParent(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    put {
      entity(as[FullyQualifiedResourceId]) { newResourceParent =>
        requireAction(resource, SamResourceActions.setParent, userInfo.userId, samRequestContext) {
          requireParentAction(resource, None, SamResourceActions.removeChild, userInfo.userId, samRequestContext) {
            requireParentAction(resource, Option(newResourceParent), SamResourceActions.addChild, userInfo.userId, samRequestContext) {
              complete(resourceService.setResourceParent(resource, newResourceParent, samRequestContext).map(_ => StatusCodes.NoContent))
            }
          }
        }
      }
    }

  def deleteResourceParent(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    delete {
      requireAction(resource, SamResourceActions.setParent, userInfo.userId, samRequestContext) {
        requireParentAction(resource, None, SamResourceActions.removeChild, userInfo.userId, samRequestContext) {
          complete(resourceService.deleteResourceParent(resource, samRequestContext).map { parentDeleted =>
            if (parentDeleted) {
              StatusCodes.NoContent
            } else {
              StatusCodes.NotFound
            }
          })
        }
      }
    }

  def getResourceChildren(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.listChildren, userInfo.userId, samRequestContext) {
        complete(resourceService.listResourceChildren(resource, samRequestContext).map(children => StatusCodes.OK -> children))
      }
    }

  def deletePolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    delete {
      requireOneOfAction(policyId.resource, Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyId.accessPolicyName)), userInfo.userId, samRequestContext) {
        complete(resourceService.deletePolicy(policyId, samRequestContext).map(_ => StatusCodes.NoContent))
      }
    }
}
