package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AccessPolicyMembershipRequest, SamUser}
import org.broadinstitute.dsde.workbench.sam.model.api.FilteredResourcesHierarchical._
import org.broadinstitute.dsde.workbench.sam.model.api.FilteredResourcesFlat._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/** Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends SamUserDirectives with SecurityDirectives with SamModelDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def resourceRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    (pathPrefix("config" / "v1" / "resourceTypes") | pathPrefix("resourceTypes")) {
      pathEndOrSingleSlash {
        getWithTelemetry(samRequestContext) {
          complete(resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet))
        }
      }
    } ~
      (pathPrefix("resources" / "v1") | pathPrefix("resource")) {
        pathPrefix(Segment) { resourceTypeName =>
          withNonAdminResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
            pathEndOrSingleSlash {
              getUserPoliciesForResourceType(resourceType, samUser, samRequestContext) ~
              postResource(resourceType, samUser, samRequestContext)
            } ~ pathPrefix(Segment) { resourceId =>
              val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))

              pathEndOrSingleSlash {
                deleteResource(resource, samUser, samRequestContext) ~
                postDefaultResource(resourceType, resource, samUser, samRequestContext)
              } ~ pathPrefix("action") {
                pathPrefix(Segment) { action =>
                  pathEndOrSingleSlash {
                    getActionPermissionForUser(resource, samUser, ResourceAction(action), samRequestContext)
                  } ~ pathPrefix("userEmail") {
                    pathPrefix(Segment) { userEmail =>
                      pathEndOrSingleSlash {
                        getActionPermissionForUserEmail(resource, samUser, ResourceAction(action), WorkbenchEmail(userEmail), samRequestContext)
                      }
                    }
                  }
                }
              } ~ pathPrefix("authDomain") {
                pathEndOrSingleSlash {
                  getResourceAuthDomain(resource, samUser, samRequestContext)
                }
              } ~ pathPrefix("policies") {
                pathEndOrSingleSlash {
                  getResourcePolicies(resource, samUser, samRequestContext)
                } ~ pathPrefix(Segment) { policyName =>
                  val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                  pathEndOrSingleSlash {
                    getPolicy(policyId, samUser, samRequestContext) ~
                    putPolicyOverwrite(resourceType, policyId, samUser, samRequestContext)
                  } ~ pathPrefix("memberEmails") {
                    pathEndOrSingleSlash {
                      putPolicyMembershipOverwrite(policyId, samUser, samRequestContext)
                    } ~ pathPrefix(Segment) { email =>
                      val workbenchEmail = WorkbenchEmail(email)
                      val memberParams = policyParams(policyId).appended(emailParam(workbenchEmail))
                      withSubject(workbenchEmail, samRequestContext) { subject =>
                        pathEndOrSingleSlash {
                          requireOneOfAction(
                            resource,
                            Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
                            samUser.id,
                            samRequestContext
                          ) {
                            putUserInPolicy(
                              policyId,
                              subject,
                              samRequestContext,
                              memberParams
                            ) ~
                            deleteUserFromPolicy(policyId, subject, samRequestContext, memberParams)
                          }
                        }
                      }
                    }
                  } ~ pathPrefix("public") {
                    pathEndOrSingleSlash {
                      getPublicFlag(policyId, samUser, samRequestContext) ~
                      putPublicFlag(policyId, samUser, samRequestContext)
                    }
                  }
                }
              } ~ pathPrefix("roles") {
                pathEndOrSingleSlash {
                  getUserResourceRoles(resource, samUser, samRequestContext)
                }
              } ~ pathPrefix("actions") {
                pathEndOrSingleSlash {
                  listActionsForUser(resource, samUser, samRequestContext)
                }
              } ~ pathPrefix("allUsers") {
                pathEndOrSingleSlash {
                  getAllResourceUsers(resource, samUser, samRequestContext)
                }
              }
            }
          }
        }
      } ~
      pathPrefix("resources" / "v2") {
        pathEnd {
          getWithTelemetry(samRequestContext) {
            listUserResources(samUser, samRequestContext)
          }
        } ~
        pathPrefix(Segment) { resourceTypeName =>
          withNonAdminResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
            pathEndOrSingleSlash {
              getUserResourcesOfType(resourceType, samUser, samRequestContext) ~
              postResource(resourceType, samUser, samRequestContext)
            } ~
            pathPrefix(Segment) { resourceId =>
              val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))

              pathEndOrSingleSlash {
                deleteResource(resource, samUser, samRequestContext) ~
                postDefaultResource(resourceType, resource, samUser, samRequestContext)
              } ~
              pathPrefix("action") {
                pathPrefix(Segment) { actionString =>
                  val action = ResourceAction(actionString)
                  pathEndOrSingleSlash {
                    getActionPermissionForUser(resource, samUser, action, samRequestContext)
                  } ~
                  pathPrefix("userEmail") {
                    pathPrefix(Segment) { userEmail =>
                      pathEndOrSingleSlash {
                        getActionPermissionForUserEmail(resource, samUser, action, WorkbenchEmail(userEmail), samRequestContext)
                      }
                    }
                  }
                }
              } ~
              pathPrefix("leave") {
                pathEndOrSingleSlash {
                  leaveResource(resourceType, resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("authDomain") {
                pathEndOrSingleSlash {
                  getResourceAuthDomain(resource, samUser, samRequestContext) ~
                  patchResourceAuthDomain(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("roles") {
                pathEndOrSingleSlash {
                  getUserResourceRoles(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("actions") {
                pathEndOrSingleSlash {
                  listActionsForUser(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("allUsers") {
                pathEndOrSingleSlash {
                  getAllResourceUsers(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("parent") {
                pathEndOrSingleSlash {
                  getResourceParent(resource, samUser, samRequestContext) ~
                  setResourceParent(resource, samUser, samRequestContext) ~
                  deleteResourceParent(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("children") {
                pathEndOrSingleSlash {
                  getResourceChildren(resource, samUser, samRequestContext)
                }
              } ~
              pathPrefix("policies") {
                pathEndOrSingleSlash {
                  getResourcePolicies(resource, samUser, samRequestContext)
                } ~ pathPrefix(Segment) { policyName =>
                  val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                  pathEndOrSingleSlash {
                    getPolicy(policyId, samUser, samRequestContext) ~
                    putPolicyOverwrite(resourceType, policyId, samUser, samRequestContext) ~
                    deletePolicy(policyId, samUser, samRequestContext)
                  } ~
                  pathPrefix("memberEmails") {
                    requireActionsForSharePolicy(policyId, samUser, samRequestContext) {
                      pathEndOrSingleSlash {
                        putPolicyMembershipOverwrite(policyId, samUser, samRequestContext)
                      } ~
                      pathPrefix(Segment) { email =>
                        val workbenchEmail = WorkbenchEmail(email)
                        val memberParams = policyParams(policyId).appended(emailParam(workbenchEmail))
                        withSubject(workbenchEmail, samRequestContext) { subject =>
                          pathEndOrSingleSlash {
                            putUserInPolicy(
                              policyId,
                              subject,
                              samRequestContext,
                              memberParams
                            ) ~
                            deleteUserFromPolicy(policyId, subject, samRequestContext, memberParams)
                          }
                        }
                      }
                    }
                  } ~
                  pathPrefix("memberPolicies") {
                    requireActionsForSharePolicy(policyId, samUser, samRequestContext) {
                      path(Segment / Segment / Segment) { (memberResourceType, memberResourceId, memberPolicyName) =>
                        val memberResource = FullyQualifiedResourceId(ResourceTypeName(memberResourceType), ResourceId(memberResourceId))
                        val policySubject = FullyQualifiedPolicyId(memberResource, AccessPolicyName(memberPolicyName))
                        val memberParams = policyParams(policyId) ++ policyParams(policySubject, "member")
                        withPolicy(policySubject, samRequestContext) { memberPolicy =>
                          pathEndOrSingleSlash {
                            putUserInPolicy(policyId, memberPolicy.id, samRequestContext, memberParams) ~
                            deleteUserFromPolicy(policyId, memberPolicy.id, samRequestContext, memberParams)
                          }
                        }
                      }
                    }
                  } ~
                  pathPrefix("public") {
                    pathEndOrSingleSlash {
                      getPublicFlag(policyId, samUser, samRequestContext) ~
                      putPublicFlag(policyId, samUser, samRequestContext)
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
  object Deprecated {
    @deprecated("remove as part of CA-1783", "") class Corral { def listUserAccessPolicies = policyEvaluatorService.listUserAccessPolicies _ };
    object Corral extends Corral
  }

  def getUserPoliciesForResourceType(resourceType: ResourceType, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceTypeParam(resourceType)) {
      complete(Deprecated.Corral.listUserAccessPolicies(resourceType.name, samUser.id, samRequestContext))
    }

  def getUserResourcesOfType(resourceType: ResourceType, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceTypeParam(resourceType)) {
      complete(resourceService.listUserResources(resourceType.name, samUser.id, samRequestContext))
    }

  def postResource(resourceType: ResourceType, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    postWithTelemetry(samRequestContext, resourceTypeParam(resourceType)) {
      entity(as[CreateResourceRequest]) { createResourceRequest =>
        requireCreateWithOptionalParent(createResourceRequest.parent, resourceType, samUser.id, samRequestContext) {
          if (resourceType.reuseIds && resourceType.isAuthDomainConstrainable) {
            throw new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, "this api may not be used for resource types that allow both authorization domains and id reuse")
            )
          }

          def resourceMaker(samRequestContext: SamRequestContext): IO[ToResponseMarshallable] = resourceService
            .createResource(
              resourceType,
              createResourceRequest.resourceId,
              createResourceRequest.policies,
              createResourceRequest.authDomain,
              createResourceRequest.parent,
              samUser.id,
              samRequestContext
            )
            .map { r =>
              if (createResourceRequest.returnResource.contains(true)) {
                StatusCodes.Created -> CreateResourceResponse(
                  r.resourceTypeName,
                  r.resourceId,
                  r.authDomain,
                  r.accessPolicies.map(ap => CreateResourcePolicyResponse(ap.id, ap.email))
                )
              } else {
                StatusCodes.NoContent
              }
            }

          complete(resourceMaker(samRequestContext))
        }
      }
    }

  def deleteResource(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    deleteWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      // Note that this does not require remove_child on the parent if it exists. remove_child is meant to prevent
      // users from removing a child only to add it to a different parent and thus circumvent any permissions
      // a parent may be enforcing. Deleting a child does not allow this situation.
      requireAction(resource, SamResourceActions.delete, samUser.id, samRequestContext) {
        complete(resourceService.deleteResource(resource, samRequestContext).map(_ => StatusCodes.NoContent))
      }
    }

  def postDefaultResource(
      resourceType: ResourceType,
      resource: FullyQualifiedResourceId,
      samUser: SamUser,
      samRequestContext: SamRequestContext
  ): server.Route =
    postWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      complete(resourceService.createResource(resourceType, resource.resourceId, samUser, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def leaveResource(resourceType: ResourceType, resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    deleteWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      if (resourceType.allowLeaving) complete(resourceService.leaveResource(resourceType, resource, samUser, samRequestContext).map(_ => StatusCodes.NoContent))
      else complete(StatusCodes.Forbidden -> s"Leaving a resource of type ${resourceType.name.value} is not supported")
    }

  def getActionPermissionForUser(
      resource: FullyQualifiedResourceId,
      samUser: SamUser,
      action: ResourceAction,
      samRequestContext: SamRequestContext
  ): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource).appended(actionParam(action)): _*) {
      complete {
        policyEvaluatorService.hasPermission(resource, action, samUser.id, samRequestContext).map { hasPermission =>
          StatusCodes.OK -> JsBoolean(hasPermission)
        }
      }
    }

  /** Checks if user has permission by giver user email.
    *
    * <p> The caller should have readPolicies, OR testAnyActionAccess or testActionAccess::{action} to make this call.
    */
  def getActionPermissionForUserEmail(
      resource: FullyQualifiedResourceId,
      samUser: SamUser,
      action: ResourceAction,
      userEmail: WorkbenchEmail,
      samRequestContext: SamRequestContext
  ): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource) ++ Seq(actionParam(action), emailParam(userEmail)): _*) {
      requireOneOfAction(
        resource,
        Set(SamResourceActions.readPolicies, SamResourceActions.testAnyActionAccess, SamResourceActions.testActionAccess(action)),
        samUser.id,
        samRequestContext
      ) {
        complete {
          policyEvaluatorService.hasPermissionByUserEmail(resource, action, userEmail, samRequestContext).map { hasPermission =>
            StatusCodes.OK -> JsBoolean(hasPermission)
          }
        }
      }
    }

  def listActionsForUser(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      complete(policyEvaluatorService.listUserResourceActions(resource, samUser.id, samRequestContext = samRequestContext).map { actions =>
        StatusCodes.OK -> actions
      })
    }

  def getResourceAuthDomain(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.readAuthDomain, samUser.id, samRequestContext) {
        complete(resourceService.loadResourceAuthDomain(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response
        })
      }
    }

  def patchResourceAuthDomain(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    patchWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.updateAuthDomain, samUser.id, samRequestContext) {
        entity(as[Set[WorkbenchGroupName]]) { authDomains =>
          complete(resourceService.addResourceAuthDomain(resource, authDomains, samUser.id, samRequestContext).map { response =>
            StatusCodes.OK -> response
          })
        }
      }
    }

  def getResourcePolicies(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.readPolicies, samUser.id, samRequestContext) {
        complete(resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response.toSet
        })
      }
    }

  def getPolicy(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireOneOfAction(
        policyId.resource,
        Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)),
        samUser.id,
        samRequestContext
      ) {
        complete(resourceService.loadResourcePolicy(policyId, samRequestContext).map {
          case Some(response) => StatusCodes.OK -> response
          case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found"))
        })
      }
    }

  def putPolicyOverwrite(resourceType: ResourceType, policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    putWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireAction(policyId.resource, SamResourceActions.alterPolicies, samUser.id, samRequestContext) {
        entity(as[AccessPolicyMembershipRequest]) { membershipUpdate =>
          complete(
            resourceService
              .overwritePolicy(resourceType, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext)
              .map(_ => StatusCodes.Created)
          )
        }
      }
    }

  private def requireActionsForSharePolicy(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext)(
      sharePolicy: server.Route
  ): server.Route =
    requireOneOfAction(
      policyId.resource,
      Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
      samUser.id,
      samRequestContext
    ) {
      sharePolicy
    }

  def putPolicyMembershipOverwrite(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    putWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireOneOfAction(
        policyId.resource,
        Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
        samUser.id,
        samRequestContext
      ) {
        entity(as[Set[WorkbenchEmail]]) { membersList =>
          complete(resourceService.overwritePolicyMembers(policyId, membersList, samRequestContext).map(_ => StatusCodes.NoContent))
        }
      }
    }

  def putUserInPolicy(
      policyId: FullyQualifiedPolicyId,
      subject: WorkbenchSubject,
      samRequestContext: SamRequestContext,
      pathParams: Seq[(String, ValueObject)]
  ): server.Route =
    putWithTelemetry(samRequestContext, pathParams: _*) {
      complete(
        resourceService.addSubjectToPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent)
      )
    }

  def deleteUserFromPolicy(
      policyId: FullyQualifiedPolicyId,
      subject: WorkbenchSubject,
      samRequestContext: SamRequestContext,
      pathParams: Seq[(String, ValueObject)]
  ): server.Route =
    deleteWithTelemetry(samRequestContext, pathParams: _*) {
      complete(resourceService.removeSubjectFromPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
    }

  def getPublicFlag(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireOneOfAction(
        policyId.resource,
        Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyId.accessPolicyName)),
        samUser.id,
        samRequestContext
      ) {
        complete(resourceService.isPublic(policyId, samRequestContext))
      }
    }

  def putPublicFlag(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    putWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireOneOfAction(
        policyId.resource,
        Set(SamResourceActions.alterPolicies, SamResourceActions.sharePolicy(policyId.accessPolicyName)),
        samUser.id,
        samRequestContext
      ) {
        requireOneOfAction(
          FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(policyId.resource.resourceTypeName.value)),
          Set(SamResourceActions.setPublic, SamResourceActions.setPublicPolicy(policyId.accessPolicyName)),
          samUser.id,
          samRequestContext
        ) {
          entity(as[Boolean]) { isPublic =>
            complete(resourceService.setPublic(policyId, isPublic, samRequestContext).map(_ => StatusCodes.NoContent))
          }
        }
      }
    }

  def getUserResourceRoles(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      complete {
        resourceService.listUserResourceRoles(resource, samUser, samRequestContext).map { roles =>
          StatusCodes.OK -> roles
        }
      }
    }

  def getAllResourceUsers(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.readPolicies, samUser.id, samRequestContext) {
        complete(resourceService.listAllFlattenedResourceUsers(resource, samRequestContext).map { allUsers =>
          StatusCodes.OK -> allUsers
        })
      }
    }

  def getResourceParent(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.getParent, samUser.id, samRequestContext) {
        complete(resourceService.getResourceParent(resource, samRequestContext).map {
          case Some(response) => StatusCodes.OK -> response
          case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "resource parent not found"))
        })
      }
    }

  def setResourceParent(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    putWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      entity(as[FullyQualifiedResourceId]) { newResourceParent =>
        requireAction(resource, SamResourceActions.setParent, samUser.id, samRequestContext) {
          requireParentAction(resource, None, SamResourceActions.removeChild, samUser.id, samRequestContext) {
            requireParentAction(resource, Option(newResourceParent), SamResourceActions.addChild, samUser.id, samRequestContext) {
              complete(resourceService.setResourceParent(resource, newResourceParent, samRequestContext).map(_ => StatusCodes.NoContent))
            }
          }
        }
      }
    }

  def deleteResourceParent(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    deleteWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.setParent, samUser.id, samRequestContext) {
        requireParentAction(resource, None, SamResourceActions.removeChild, samUser.id, samRequestContext) {
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

  def getResourceChildren(resource: FullyQualifiedResourceId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
      requireAction(resource, SamResourceActions.listChildren, samUser.id, samRequestContext) {
        complete(resourceService.listResourceChildren(resource, samRequestContext).map(children => StatusCodes.OK -> children))
      }
    }

  def deletePolicy(policyId: FullyQualifiedPolicyId, samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    deleteWithTelemetry(samRequestContext, policyParams(policyId): _*) {
      requireOneOfAction(
        policyId.resource,
        Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyId.accessPolicyName)),
        samUser.id,
        samRequestContext
      ) {
        complete(resourceService.deletePolicy(policyId, samRequestContext).map(_ => StatusCodes.NoContent))
      }
    }

  private def listUserResources(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    parameters(
      "resourceTypes".as[String].?,
      "policies".as[String].?,
      "roles".as[String].?,
      "actions".as[String].?,
      "includePublic" ? false,
      "format".as[String] ? "hierarchical"
    ) { (resourceTypes: Option[String], policies: Option[String], roles: Option[String], actions: Option[String], includePublic: Boolean, format: String) =>
      format match {
        case "flat" =>
          complete {
            resourceService
              .listResourcesFlat(
                samUser.id,
                resourceTypes.map(_.split(",").map(ResourceTypeName(_)).toSet).getOrElse(Set.empty),
                policies.map(_.split(",").map(AccessPolicyName(_)).toSet).getOrElse(Set.empty),
                roles.map(_.split(",").map(ResourceRoleName(_)).toSet).getOrElse(Set.empty),
                actions.map(_.split(",").map(ResourceAction(_)).toSet).getOrElse(Set.empty),
                includePublic,
                samRequestContext
              )
              .map(StatusCodes.OK -> _)
          }
        case "hierarchical" =>
          complete {
            resourceService
              .listResourcesHierarchical(
                samUser.id,
                resourceTypes.map(_.split(",").map(ResourceTypeName(_)).toSet).getOrElse(Set.empty),
                policies.map(_.split(",").map(AccessPolicyName(_)).toSet).getOrElse(Set.empty),
                roles.map(_.split(",").map(ResourceRoleName(_)).toSet).getOrElse(Set.empty),
                actions.map(_.split(",").map(ResourceAction(_)).toSet).getOrElse(Set.empty),
                includePublic,
                samRequestContext
              )
              .map(StatusCodes.OK -> _)
          }
      }
    }
}
