package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.Generator.genResourceId
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.mockito.scalatest.MockitoSugar

import java.util.UUID
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestResourceServiceBuilder(
    policyEvaluatorService: PolicyEvaluatorService,
    accessPolicyDAO: AccessPolicyDAO,
    directoryDAO: DirectoryDAO,
    cloudExtensions: CloudExtensions
)(implicit val executionContext: ExecutionContext, val openTelemetry: OpenTelemetryMetrics[IO])
    extends MockitoSugar {
  private val emailDomain = "example.com"
  private var maybeDummySamUser: Option[SamUser] = None
  private var maybeDefaultResourceType: Option[ResourceType] = None
  private var maybeOtherResourceType: Option[ResourceType] = None
  private var maybeWorkspaceResourceType: Option[ResourceType] = None
  private[service] val defaultResourceTypeActions =
    Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  private[service] val defaultResourceTypeActionPatterns = Set(
    SamResourceActionPatterns.alterPolicies,
    SamResourceActionPatterns.delete,
    SamResourceActionPatterns.readPolicies,
    ResourceActionPattern("view", "", false),
    ResourceActionPattern("non_owner_action", "", false)
  )

  var maybePolicyEvaluatorService: Option[PolicyEvaluatorService] = None

  def withRandomDefaultResourceType(): TestResourceServiceBuilder = {
    maybeDefaultResourceType = Some(
      ResourceType(
        ResourceTypeName(UUID.randomUUID().toString),
        defaultResourceTypeActionPatterns,
        Set(
          ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
          ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
        ),
        ResourceRoleName("owner")
      )
    )
    this
  }

  def withRandomOtherResourceType(): TestResourceServiceBuilder = {
    maybeOtherResourceType = Some(
      ResourceType(
        ResourceTypeName(UUID.randomUUID().toString),
        defaultResourceTypeActionPatterns,
        Set(
          ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
          ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
        ),
        ResourceRoleName("owner")
      )
    )
    this
  }

  def withRandomWorkspaceResourceType(): TestResourceServiceBuilder = {
    maybeWorkspaceResourceType = Some(
      ResourceType(
        SamResourceTypes.workspaceName,
        defaultResourceTypeActionPatterns,
        Set(
          ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
          ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
        ),
        ResourceRoleName("workspace")
      )
    )
    this
  }

  def withDummySamUser(samUser: SamUser): TestResourceServiceBuilder = {
    maybeDummySamUser = Some(samUser)
    this
  }

  def build: ResourceService = {
    val resourceTypes: mutable.Map[ResourceTypeName, ResourceType] = new TrieMap()
    val resources: mutable.Map[ResourceType, FullyQualifiedResourceId] = new TrieMap()

    maybeDefaultResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
        resources += rt -> FullyQualifiedResourceId(rt.name, genResourceId.sample.get)
      case _ => ()
    }

    maybeOtherResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
        resources += rt -> FullyQualifiedResourceId(rt.name, genResourceId.sample.get)
      case _ => ()
    }

    maybeWorkspaceResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
        resources += rt -> FullyQualifiedResourceId(rt.name, genResourceId.sample.get)
      case _ => ()
    }

    // val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypes.toMap, accessPolicyDAO, directoryDAO)
    val resourceService = new ResourceService(resourceTypes.toMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain, Set())

    //resourceTypes.collect {
    //  case (_, resourceType) => resourceService.createResourceType(resourceType, SamRequestContext())
    //}

    /*resources.collect {
      case (resourceType, fqResourceId) =>
        resourceService.createResource(
          resourceType,
          fqResourceId.resourceId,
          maybeDummySamUser.get,
          SamRequestContext())
        resourceService.overwritePolicy(
          resourceType,
          AccessPolicyName("in-it"),
          fqResourceId,
          AccessPolicyMembership(Set(maybeDummySamUser.get.email), Set(ResourceAction("alter_policies")), Set.empty),
          SamRequestContext()
        )
        resourceService.overwritePolicy(
          resourceType,
          AccessPolicyName("not-in-it"),
          fqResourceId,
          AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty),
          SamRequestContext()
        )
    }*/

    resourceService
  }
}

class ResourceService1(
    resourceTypes: Map[ResourceTypeName, ResourceType],
    override val policyEvaluatorService: PolicyEvaluatorService,
    accessPolicyDAO: AccessPolicyDAO,
    directoryDAO: DirectoryDAO,
    cloudExtensions: CloudExtensions,
    emailDomain: String,
    allowedAdminEmailDomains: Set[String]
)(implicit executionContext: ExecutionContext, openTelemetry: OpenTelemetryMetrics[IO])
    extends ResourceService(resourceTypes, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain, allowedAdminEmailDomains)(
      executionContext,
      openTelemetry
    )
