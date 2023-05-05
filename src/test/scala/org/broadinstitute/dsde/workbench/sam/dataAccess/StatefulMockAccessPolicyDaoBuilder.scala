package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, FullyQualifiedPolicyId, FullyQualifiedResourceId, ResourceIdWithRolesAndActions, ResourceTypeName, RolesAndActions}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

case class StatefulMockAccessPolicyDaoBuilder() extends MockitoSugar {
  private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()

  val mockedAccessPolicyDAO: AccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val policy = invocation.getArgument[AccessPolicy](0)
      policies += policy.id -> policy
      makePolicyExist(policy)
      IO(policy)
    }
    .when(mockedAccessPolicyDAO)
    .createPolicy(any[AccessPolicy], any[SamRequestContext])

  private def makePolicyExist(policy: AccessPolicy): Unit = {
    doThrow(new RuntimeException(s"Policy $policy is mocked to already exist"))
      .when(mockedAccessPolicyDAO)
      .createPolicy(ArgumentMatchers.eq(policy), any[SamRequestContext])

    lenient()
      .doReturn(IO.pure(Option(policy)))
      .when(mockedAccessPolicyDAO)
      .loadPolicy(ArgumentMatchers.eq(policy.id), any[SamRequestContext])

    policy.members.foreach(m => {
      lenient()
        .doAnswer { (i: InvocationOnMock) =>
          val resourceTypeName = i.getArgument[ResourceTypeName](0)
          val workbenchUserId = i.getArgument[WorkbenchUserId](1)
          println("policies.size: "+policies.size)
          IO {
            val forEachPolicy = policies.collect {
              case (fqPolicyId@FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy: AccessPolicy)
                if accessPolicy.members.contains(workbenchUserId) || accessPolicy.public =>
                if (accessPolicy.public) {
                  ResourceIdWithRolesAndActions(fqPolicyId.resource.resourceId, RolesAndActions.empty, RolesAndActions.empty, RolesAndActions.fromPolicy(accessPolicy))
                } else {
                  ResourceIdWithRolesAndActions(fqPolicyId.resource.resourceId, RolesAndActions.fromPolicy(accessPolicy), RolesAndActions.empty, RolesAndActions.empty)
                }
            }
            forEachPolicy.groupBy(_.resourceId).map { case (resourceId, rowsForResource) =>
              rowsForResource.reduce { (left, right) =>
                ResourceIdWithRolesAndActions(resourceId, left.direct ++ right.direct, left.inherited ++ right.inherited, left.public ++ right.public)
              }
            }
          }
        }
        .when(mockedAccessPolicyDAO)
        .listUserResourcesWithRolesAndActions(
          ArgumentMatchers.eq(policy.id.resource.resourceTypeName),
          ArgumentMatchers.eq(WorkbenchUserId(m.toString)),
          any[SamRequestContext])
    })
  }

  def withAccessPolicy(policy: AccessPolicy): StatefulMockAccessPolicyDaoBuilder = {
    policies += policy.id -> policy
    mockedAccessPolicyDAO.createPolicy(policy, SamRequestContext())
    this
  }

  def build: AccessPolicyDAO = mockedAccessPolicyDAO
}
