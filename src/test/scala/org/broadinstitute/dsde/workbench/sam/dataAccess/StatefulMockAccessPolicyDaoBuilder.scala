package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.{genAccessPolicyName, genNonPetEmail, genResourceId}
import org.broadinstitute.dsde.workbench.sam.matchers.AnyOfMatcher
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

case class StatefulMockAccessPolicyDaoBuilder() extends MockitoSugar {
  val mockedAccessPolicyDAO: AccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val policy = invocation.getArgument[AccessPolicy](0)
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

    lenient()
      .doAnswer { (i: InvocationOnMock) =>
        val resourceTypeName = i.getArgument[ResourceTypeName](0)
        val workbenchUserId = i.getArgument[WorkbenchUserId](1)
        val policies = Map(policy.id -> policy)

        IO {
          val forEachPolicy = policies.collect {
            case (FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy: AccessPolicy)
                if accessPolicy.members.contains(workbenchUserId) || accessPolicy.public =>
              constructResourceIdWithRolesAndActions(accessPolicy)
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
        // ArgumentMatchers.eq(WorkbenchUserId(m.toString)),
        argThat(new AnyOfMatcher(policy.members.map(m => WorkbenchUserId(m.toString)))),
        any[SamRequestContext]
      )
  }

  private def constructResourceIdWithRolesAndActions(accessPolicy: AccessPolicy): ResourceIdWithRolesAndActions =
    if (accessPolicy.public) {
      ResourceIdWithRolesAndActions(
        accessPolicy.id.resource.resourceId,
        RolesAndActions.empty,
        RolesAndActions.empty,
        RolesAndActions.fromPolicy(accessPolicy)
      )
    } else {
      ResourceIdWithRolesAndActions(
        accessPolicy.id.resource.resourceId,
        RolesAndActions.fromPolicy(accessPolicy),
        RolesAndActions.empty,
        RolesAndActions.empty
      )
    }

  def withRandomAccessPolicy(resourceTypeName: ResourceTypeName, members: Set[WorkbenchSubject]): StatefulMockAccessPolicyDaoBuilder = {
    val policy = AccessPolicy(
      FullyQualifiedPolicyId(
        FullyQualifiedResourceId(resourceTypeName, genResourceId.sample.get),
        genAccessPolicyName.sample.get
      ),
      members,
      genNonPetEmail.sample.get,
      Set(),
      Set(),
      Set(),
      false
    )
    mockedAccessPolicyDAO.createPolicy(policy, SamRequestContext())
    this
  }

  def build: AccessPolicyDAO = mockedAccessPolicyDAO
}
