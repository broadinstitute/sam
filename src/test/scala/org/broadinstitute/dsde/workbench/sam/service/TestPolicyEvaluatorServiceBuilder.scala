package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.mockito.scalatest.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestPolicyEvaluatorServiceBuilder(directoryDAO: DirectoryDAO, policyDAO: AccessPolicyDAO)(implicit
    val executionContext: ExecutionContext,
    val openTelemetry: OpenTelemetryMetrics[IO]
) extends MockitoSugar {
  private val existingPolicies: mutable.Set[AccessPolicy] = mutable.Set.empty
  private val emailDomain = "example.com"
  private val resourceTypes: mutable.Map[ResourceTypeName, ResourceType] = new TrieMap()

  private val defaultResourceTypeActions =
    Set(
      ResourceAction("alter_policies"),
      ResourceAction("delete"),
      ResourceAction("read_policies"),
      ResourceAction("view"),
      ResourceAction("non_owner_action")
    )
  private val defaultResourceTypeActionPatterns = Set(
    SamResourceActionPatterns.alterPolicies,
    SamResourceActionPatterns.delete,
    SamResourceActionPatterns.readPolicies,
    ResourceActionPattern("view", "", false),
    ResourceActionPattern("non_owner_action", "", false)
  )
  private val workspaceResourceType = ResourceType(
    SamResourceTypes.workspaceName,
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )

  def withExistingPolicy(policy: AccessPolicy): TestPolicyEvaluatorServiceBuilder = withExistingPolicies(List(policy))
  def withExistingPolicies(policies: Iterable[AccessPolicy]): TestPolicyEvaluatorServiceBuilder = {
    existingPolicies.addAll(policies)
    this
  }

  def withResourceTypes(resourceTypeCollection: Set[ResourceType]): TestPolicyEvaluatorServiceBuilder = {
    resourceTypeCollection.foreach(rt => resourceTypes += rt.name -> rt)
    this
  }

  def build: PolicyEvaluatorService =
    spy(new PolicyEvaluatorService(emailDomain, resourceTypes.toMap, policyDAO, directoryDAO))

}
