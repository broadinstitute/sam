package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global

/** Created by tlangs on 2023/10/31.
  */
class ResourceServiceUnitSpec extends AnyFlatSpec with Matchers with ScalaFutures with TestSupport with MockitoSugar with PropertyBasedTesting {

  private val dummyUser = Generator.genWorkbenchUserBoth.sample.get
  private val emailDomain = "example.com"
  private val resourceTypeName = ResourceTypeName("awesomeType")
  private val readerRoleName = ResourceRoleName("reader")
  private val readAction = ResourceAction("read")
  private val writeAction = ResourceAction("write")
  private val ownerRoleName = ResourceRoleName("owner")
  private val nothingRoleName = ResourceRoleName("cantDoNuthin")

  val testResourceId = ResourceId(UUID.randomUUID().toString)
  val testPolicy1 :: testPolicy2 :: testPolicy3 :: testPolicy4 :: testPolicy5 :: _ = (1 to 6).map(_ => AccessPolicyName(UUID.randomUUID().toString)).toList
  val dbResult = Seq(
    // Some things we don't care about
    FilterResourcesResult(
      ResourceId(UUID.randomUUID().toString),
      resourceTypeName,
      Some(AccessPolicyName(UUID.randomUUID().toString)),
      Some(readerRoleName),
      Some(readAction),
      true
    ),
    FilterResourcesResult(ResourceId(UUID.randomUUID().toString), resourceTypeName, Some(AccessPolicyName(UUID.randomUUID().toString)), None, None, true),
    FilterResourcesResult(
      ResourceId(UUID.randomUUID().toString),
      resourceTypeName,
      Some(AccessPolicyName(UUID.randomUUID().toString)),
      Some(readerRoleName),
      Some(readAction),
      false
    ),
    FilterResourcesResult(ResourceId(UUID.randomUUID().toString), resourceTypeName, Some(AccessPolicyName(UUID.randomUUID().toString)), None, None, false),
    // Testable DB Results
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy1), Some(readerRoleName), Some(readAction), false),
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy2), Some(nothingRoleName), None, true),
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy3), None, None, false),
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy4), Some(ownerRoleName), Some(readAction), false),
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy4), Some(ownerRoleName), Some(writeAction), false),
    FilterResourcesResult(testResourceId, resourceTypeName, Some(testPolicy5), None, Some(readAction), true)
  )

  val mockAccessPolicyDAO = mock[AccessPolicyDAO]
  when(
    mockAccessPolicyDAO.filterResources(
      any[SamUser],
      any[Set[ResourceTypeName]],
      any[Set[AccessPolicyName]],
      any[Set[ResourceRoleName]],
      any[Set[ResourceAction]],
      any[Boolean],
      any[SamRequestContext]
    )
  )
    .thenReturn(IO.pure(dbResult))

  val resourceService = new ResourceService(
    Map.empty,
    mock[PolicyEvaluatorService],
    mockAccessPolicyDAO,
    mock[DirectoryDAO],
    NoExtensions,
    emailDomain,
    Set("test.firecloud.org")
  )

  "ResourceService" should "group filtered resources from the database appropriately flatly" in {
    val filteredResources = resourceService.filterResourcesFlat(dummyUser, Set.empty, Set.empty, Set.empty, Set.empty, true, samRequestContext).unsafeRunSync()

    val oneResource = filteredResources.resources.filter(_.resourceId.equals(testResourceId)).head
    oneResource.resourceType should be(resourceTypeName)
    oneResource.policies should be(Set(testPolicy1, testPolicy2, testPolicy3, testPolicy4, testPolicy5))
    oneResource.roles should be(Set(readerRoleName, ownerRoleName, nothingRoleName))
    oneResource.actions should be(Set(readAction, writeAction))
  }

  it should "group filtered resources from the database appropriately hierarchically" in {

    val filteredResources =
      resourceService.filterResourcesHierarchical(dummyUser, Set.empty, Set.empty, Set.empty, Set.empty, true, samRequestContext).unsafeRunSync()

    val oneResource = filteredResources.resources.filter(_.resourceId.equals(testResourceId)).head
    oneResource.resourceType should be(resourceTypeName)

    val policies = oneResource.policies
    policies.map(_.policy) should be(Set(testPolicy1, testPolicy2, testPolicy3, testPolicy4, testPolicy5))
    val policyWithAction = policies.filter(_.policy.equals(testPolicy5)).head
    policyWithAction.roles should be(Set.empty)
    policyWithAction.actions should be(Set(readAction))

    val policyWithRoles = policies.filter(_.policy.equals(testPolicy4)).head
    val role = policyWithRoles.roles.head
    role.role should be(ownerRoleName)
    role.actions should be(Set(readAction, writeAction))
  }
}
