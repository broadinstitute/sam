package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.{genAccessPolicyName, genNonPetEmail, genResourceId}
import org.broadinstitute.dsde.workbench.sam.matchers.MatchesOneOf
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

// TODO: Stateful mocks are not what we want.  For now, we are implementing it this way so that we can incrementally
//  improve tests and production code alike.  First step is to get this MockBuilder in place as it helps us identify
//  where we should try to refactor production code.  Note that this class is named like `StatefulFoo` intentionally
//  to call out that this Builder is being naughty and coordinating state and side-effects and may cause problems as
//  a result.
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

    // TODO: Refactor AccessPolicyDao to be less stateful/side-effecty.
    //  In general, anytime we need to mock with a .doAnswer{}, we should consider that a code smell and an indication
    //  that our production code is not designed properly.  This is wayyyy more logic than we want in a mock.
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
        argThat(MatchesOneOf(policy.members.map(m => WorkbenchUserId(m.toString)))),
        any[SamRequestContext]
      )

    // This logic should probably hit a real database. The complexity of mocking this is pretty bad.
    lenient()
      .doAnswer { (i: InvocationOnMock) =>
        val workbenchUserId = i.getArgument[WorkbenchUserId](0)
        val resourceTypeName = i.getArgument[Set[ResourceTypeName]](1).head
        val policies = Map(policy.id -> policy)

        IO {
          val forEachPolicy = policies.collect {
            case (FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy: AccessPolicy)
                if accessPolicy.members.contains(workbenchUserId) || accessPolicy.public =>
              constructFilterResourcesResult(accessPolicy)
          }

          forEachPolicy.flatten
        }

      }
      .when(mockedAccessPolicyDAO)
      .filterResources(
        argThat(MatchesOneOf(policy.members.map(m => WorkbenchUserId(m.toString)))),
        ArgumentMatchers.eq(Set(policy.id.resource.resourceTypeName)),
        ArgumentMatchers.eq(Set.empty),
        ArgumentMatchers.eq(Set.empty),
        ArgumentMatchers.eq(Set.empty),
        ArgumentMatchers.eq(true),
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

  private def constructFilterResourcesResult(accessPolicy: AccessPolicy): Seq[FilterResourcesResult] =
    if (accessPolicy.roles.isEmpty) {
      Seq(
        FilterResourcesResult(
          accessPolicy.id.resource.resourceId,
          accessPolicy.id.resource.resourceTypeName,
          Some(accessPolicy.id.accessPolicyName),
          None,
          None,
          accessPolicy.public,
          None,
          false,
          false
        )
      )
    } else
      {
        accessPolicy.roles.map { role =>
          FilterResourcesResult(
            accessPolicy.id.resource.resourceId,
            accessPolicy.id.resource.resourceTypeName,
            Some(accessPolicy.id.accessPolicyName),
            Some(role),
            None,
            accessPolicy.public,
            None,
            false,
            false
          )
        }.toSeq
      } ++
        (if (accessPolicy.actions.isEmpty) {
           Seq(
             FilterResourcesResult(
               accessPolicy.id.resource.resourceId,
               accessPolicy.id.resource.resourceTypeName,
               Some(accessPolicy.id.accessPolicyName),
               None,
               None,
               accessPolicy.public,
               None,
               false,
               false
             )
           )
         } else {
           accessPolicy.actions.map { action =>
             FilterResourcesResult(
               accessPolicy.id.resource.resourceId,
               accessPolicy.id.resource.resourceTypeName,
               Some(accessPolicy.id.accessPolicyName),
               None,
               Some(action),
               accessPolicy.public,
               None,
               false,
               false
             )

           }.toSeq
         })

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
