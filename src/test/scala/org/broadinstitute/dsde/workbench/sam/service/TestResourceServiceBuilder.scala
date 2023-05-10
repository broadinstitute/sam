package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
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

    maybeDefaultResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
      case _ => ()
    }

    maybeOtherResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
      case _ => ()
    }

    maybeWorkspaceResourceType match {
      case Some(rt) =>
        resourceTypes += rt.name -> rt
      case _ => ()
    }

    new ResourceService(resourceTypes.toMap, policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain, Set())
  }
}
