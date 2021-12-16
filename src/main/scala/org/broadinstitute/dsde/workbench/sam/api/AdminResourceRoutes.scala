package org.broadinstitute.dsde.workbench.sam
package api

import java.time.LocalDateTime

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext

trait AdminResourceRoutes extends UserInfoDirectives with SecurityDirectives with SamRequestContextDirectives with ResourceRoutes with LazyLogging{
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def adminResourceRoutes: server.Route =
    pathPrefix("resourceTypeAdmin" / "v1") {
      withSamRequestContext { samRequestContext =>
        extractClientIP { ip =>
          requireUserInfo(samRequestContext) { userInfo =>
            withResourceType(SamResourceTypes.resourceTypeAdminName) { resourceTypeAdmin =>
              pathPrefix("resourceTypes") {
                asSamSuperAdmin(userInfo) {
                  pathPrefix(Segment) { resourceTypeNameToAdminister =>
                    withResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
                      val resource = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceTypeToAdminister.name.value))
                      pathPrefix("policies") {
                        pathEndOrSingleSlash {
                          get {
                            complete(resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
                              StatusCodes.OK -> response.toSet
                            })
                          }
                        } ~
                          pathPrefix(Segment) { policyName =>
                            val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))
                            pathEndOrSingleSlash {
                              put {
                                entity(as[AccessPolicyMembership]) { membershipUpdate =>
                                  logger.info(s"IP: ${ip.toOption.map(_.getHostAddress)} OR ${ip.toIP.map(_.ip.getHostAddress).getOrElse("toIP doesn't know")} OR $ip")
                                  logger.info(s"${LocalDateTime.now()} - ip - ${userInfo.userEmail.value} - lots of details about the difference b/w this policy and the existing policy")
                                  complete(resourceService.overwriteAdminPolicy(resourceTypeAdmin, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext).map(_ => StatusCodes.Created))
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
              } ~
                pathPrefix("resources") {
                  pathPrefix(Segment) { resourceTypeNameToAdminister =>
                    withResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
                      pathPrefix(Segment) { resourceId =>
                        val resource = FullyQualifiedResourceId(resourceTypeToAdminister.name, ResourceId(resourceId))

                        pathPrefix("policies") {
                          pathEndOrSingleSlash {
                            val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeNameToAdminister), ResourceId(resourceId))
                            getAdminResourcePolicies(resource, userInfo, samRequestContext)
                          } ~
                            pathPrefix(Segment) { policyName =>
                              val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))

                              pathPrefix("memberEmails" / Segment) { userEmail =>
                                withSubject(WorkbenchEmail(userEmail), samRequestContext) { subject =>
                                  put {
                                    requireActionsForAdminPutUserInPolicy(policyId, userInfo, samRequestContext) {
                                      adminPutUserInPolicy(policyId, subject, samRequestContext)
                                    }
                                  } ~
                                    delete {
                                      requireActionsForAdminRemoveUserFromPolicy(policyId, userInfo, samRequestContext) {
                                        adminRemoveUserFromPolicy(policyId, subject, samRequestContext)
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
        }
      }
    }

  private def getAdminResourcePolicies(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(resource.resourceTypeName.value)), SamResourceActions.adminReadPolicies, userInfo.userId, samRequestContext) {
        complete(resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response.toSet
        })
      }
    }

  private def requireActionsForAdminPutUserInPolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext)(addUserToPolicy: server.Route): server.Route =
    requireAction(
      FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(policyId.resource.resourceTypeName.value)),
      SamResourceActions.adminAddMember,
      userInfo.userId,
      samRequestContext
    ) {
      addUserToPolicy
    }

  private def requireActionsForAdminRemoveUserFromPolicy(policyId: FullyQualifiedPolicyId, userInfo: UserInfo, samRequestContext: SamRequestContext)(removeUserFromPolicy: server.Route): server.Route =
    requireAction(
      FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(policyId.resource.resourceTypeName.value)),
      SamResourceActions.adminRemoveMember,
      userInfo.userId,
      samRequestContext
    ) {
      removeUserFromPolicy
    }

  def adminPutUserInPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): server.Route =
    complete(resourceService.addSubjectToPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))

  def adminRemoveUserFromPolicy(policyId: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): server.Route =
    complete(resourceService.removeSubjectFromPolicy(policyId, subject, samRequestContext).map(_ => StatusCodes.NoContent))
}
