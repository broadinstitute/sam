package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, FullyQualifiedResourceId, Resource, ResourceType}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.{IdiomaticMockito, Strictness}

object MockAccessPolicyDaoBuilder {
  def apply() =
    new MockAccessPolicyDaoBuilder()
}

case class MockAccessPolicyDaoBuilder() extends IdiomaticMockito {
  val mockedAccessPolicyDAO: AccessPolicyDAO = mock[AccessPolicyDAO](withSettings.strictness(Strictness.Lenient))

  mockedAccessPolicyDAO.upsertResourceTypes(any[Set[ResourceType]], any[SamRequestContext]) answers ((resourceTypes: Set[ResourceType], _: SamRequestContext) =>
    IO(resourceTypes.map(types => types.name))
  )

  mockedAccessPolicyDAO.createResource(any[Resource], any[SamRequestContext]) answers ((resource: Resource, _: SamRequestContext) => IO(resource))
  def withExistingPolicy(policy: AccessPolicy): MockAccessPolicyDaoBuilder = withExistingPolicies(Set(policy))
  def withExistingPolicies(policies: Iterable[AccessPolicy]): MockAccessPolicyDaoBuilder = {
    policies.toSet.foreach(makePolicyExist)
    this
  }

  def withExistingResource(resource: Resource): MockAccessPolicyDaoBuilder = withExistingResources(Set(resource))

  def withExistingResources(resources: Iterable[Resource]): MockAccessPolicyDaoBuilder = {
    resources.toSet.foreach(makeResourceExist)
    this
  }

  private def makePolicyExist(policy: AccessPolicy): Unit =
    mockedAccessPolicyDAO.loadPolicy(eqTo(policy.id), any[SamRequestContext]) returns IO(Option(policy))

  private def makeResourceExist(resource: Resource): Unit =
    mockedAccessPolicyDAO.listUserResourceActions(
      eqTo(FullyQualifiedResourceId(resource.resourceTypeName, resource.resourceId)),
      any[WorkbenchUserId],
      any[SamRequestContext]
    ) answers { (_: FullyQualifiedResourceId, _: WorkbenchUserId, _: SamRequestContext) =>
      IO(resource.accessPolicies.flatMap(policy => policy.actions))
    }

  def build: AccessPolicyDAO = mockedAccessPolicyDAO
}
