package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.{genAccessPolicyName, genNonPetEmail, genResourceId}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

case class StatefulMockAccessPolicyDaoBuilder() extends MockitoSugar {
  // private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()

  val mockedAccessPolicyDAO: AccessPolicyDAO = mock[AccessPolicyDAO](RETURNS_SMART_NULLS)

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val policy = invocation.getArgument[AccessPolicy](0)
      // policies += policy.id -> policy
      makePolicyExist(policy)
      IO(policy)
    }
    .when(mockedAccessPolicyDAO)
    .createPolicy(any[AccessPolicy], any[SamRequestContext])

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val resource = invocation.getArgument[Resource](0)
      // policies += policy.id -> policy
      makeResourceExist(resource)
      resource.accessPolicies.foreach(makePolicyExist)
      IO(resource)
    }
    .when(mockedAccessPolicyDAO)
    .createResource(any[Resource], any[SamRequestContext])

  private def makeResourceExist(resource: Resource): Unit = {
    doThrow(new RuntimeException(s"Resource $resource is mocked to already exist"))
      .when(mockedAccessPolicyDAO)
      .createResource(ArgumentMatchers.eq(resource), any[SamRequestContext])

    resource.accessPolicies.foreach { p =>
      lenient()
        .doReturn(IO.pure(resource.accessPolicies.to(LazyList)))
        .when(mockedAccessPolicyDAO)
        .listAccessPolicies(ArgumentMatchers.eq(p.id.resource), any[SamRequestContext])
    }
  }

  private def makePolicyExist(policy: AccessPolicy): Unit = {
    doThrow(new RuntimeException(s"Policy $policy is mocked to already exist"))
      .when(mockedAccessPolicyDAO)
      .createPolicy(ArgumentMatchers.eq(policy), any[SamRequestContext])

    lenient()
      .doReturn(IO.pure(Option(policy)))
      .when(mockedAccessPolicyDAO)
      .loadPolicy(ArgumentMatchers.eq(policy.id), any[SamRequestContext])

    policy.members.foreach { m =>
      lenient()
        .doAnswer { (i: InvocationOnMock) =>
          val resourceTypeName = i.getArgument[ResourceTypeName](0)
          val workbenchUserId = i.getArgument[WorkbenchUserId](1)
          // println("policies.size: " + policies.size)
          val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()
          policies += policy.id -> policy
          IO {
            val forEachPolicy = policies.collect {
              case (fqPolicyId @ FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy: AccessPolicy)
                  if accessPolicy.members.contains(workbenchUserId) || accessPolicy.public =>
                if (accessPolicy.public) {
                  ResourceIdWithRolesAndActions(
                    fqPolicyId.resource.resourceId,
                    RolesAndActions.empty,
                    RolesAndActions.empty,
                    RolesAndActions.fromPolicy(accessPolicy)
                  )
                } else {
                  ResourceIdWithRolesAndActions(
                    fqPolicyId.resource.resourceId,
                    RolesAndActions.fromPolicy(accessPolicy),
                    RolesAndActions.empty,
                    RolesAndActions.empty
                  )
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
          any[SamRequestContext]
        )
    }
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
    // policies += policy.id -> policy
    mockedAccessPolicyDAO.createPolicy(policy, SamRequestContext())
    this
  }

  def build: AccessPolicyDAO = mockedAccessPolicyDAO
}
