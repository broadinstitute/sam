package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.genGoogleSubjectId
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{AppendedClues, FlatSpec, Matchers}


class ResourceRoutesV2Spec extends FlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {

  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo) = {
    val accessPolicyDAO = new MockAccessPolicyDAO()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()
    val emailDomain = "example.com"

    val policyEvaluatorService = mock[PolicyEvaluatorService]
    val mockResourceService = mock[ResourceService]
    resourceTypes.map { case (resourceTypeName, resourceType) =>
      when(mockResourceService.getResourceType(resourceTypeName)).thenReturn(IO(Option(resourceType)))
    }
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, NoExtensions, emailDomain)

    mockUserService.createUser(CreateWorkbenchUser(defaultUserInfo.userId, genGoogleSubjectId(), defaultUserInfo.userEmail, None), samRequestContext)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }

  private def setupRoutesTest(samRoutes: SamRoutes,
                              childResource: FullyQualifiedResourceId,
                              currentParentOpt: Option[FullyQualifiedResourceId] = None,
                              newParentOpt: Option[FullyQualifiedResourceId] = None,
                              actionsOnChild: Set[ResourceAction] = Set.empty,
                              actionsOnCurrentParent: Set[ResourceAction] = Set.empty,
                              actionsOnNewParent: Set[ResourceAction] = Set.empty,
                              missingActionsOnChild: Set[ResourceAction] = Set.empty,
                              missingActionsOnCurrentParent: Set[ResourceAction] = Set.empty,
                              missingActionsOnNewParent: Set[ResourceAction] = Set.empty,
                              accessToChild: Boolean = true,
                              accessToCurrentParent: Boolean = true,
                              accessToNewParent: Boolean = true): Unit = {
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(childResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)

    actionsOnChild.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(childResource), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true)))
    missingActionsOnChild.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(childResource), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false)))
    if (accessToChild) {
      when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(childResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
        .thenReturn(IO(Set(otherPolicy)))
    }

    currentParentOpt match {
      case Some(currentParent) =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(Option(currentParent)))
        actionsOnCurrentParent.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParent), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
          .thenReturn(IO(true)))
        missingActionsOnCurrentParent.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParent), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
          .thenReturn(IO(false)))
        if (accessToCurrentParent) {
          when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(currentParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
            .thenReturn(IO(Set(otherPolicy)))
        }
      case None =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(None))
    }

    newParentOpt.map { newParent =>
      actionsOnNewParent.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParent), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
        .thenReturn(IO(true)))
      missingActionsOnNewParent.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParent), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
        .thenReturn(IO(false)))
      if (accessToNewParent) {
        when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(newParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
          .thenReturn(IO(Set(otherPolicy)))
      }
    }
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "200 if user has get_parent on resource and resource has parent" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(fullyQualifiedParentResource)))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.getParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))

    Get(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[FullyQualifiedResourceId] shouldEqual fullyQualifiedParentResource
    }
  }

  it should "403 if user is missing get_parent on resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(fullyQualifiedParentResource)))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.getParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))

    Get(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(None))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.getParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))

    Get(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  // this one feels a bit like it's testing the security directives not the route itself but whatever
  it should "404 if user doesn't have access to resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.getParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))

    Get(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success when there is not a parent already set" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(fullyQualifiedParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(None))

    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", fullyQualifiedParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 on success when there is a parent already set" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))

    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  // test in ResourceService?
  ignore should "400 if child resource has an auth domain" in {}

  // test in PostgresAccessPolicyDAO?
  ignore should "400 if adding parent would introduce a cyclical resource hierarchy" in {}

  it should "403 if user is missing set_parent on child resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing add_child on new parent resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(newParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on existing parent resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(currentParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to new parent resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(newParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to existing parent resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.setResourceParent(mockitoEq(fullyQualifiedChildResource), mockitoEq(newParentResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(newParentResource), mockitoEq(SamResourceActions.addChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(currentParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))


    Put(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing set_parent on child resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on parent resource if it exists" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(currentParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set(AccessPolicyWithoutMembers(FullyQualifiedPolicyId(fullyQualifiedChildResource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false))))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(None))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(fullyQualifiedChildResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to existing parent resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
      ResourceRoleName("owner")
    )
    val fullyQualifiedChildResource = FullyQualifiedResourceId(resourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(resourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    when(samRoutes.resourceService.deleteResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO.unit)
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(fullyQualifiedChildResource), mockitoEq(SamResourceActions.setParent), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true))
    when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(currentParentResource), mockitoEq(SamResourceActions.removeChild), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false))
    when(samRoutes.resourceService.getResourceParent(mockitoEq(fullyQualifiedChildResource), any[SamRequestContext]))
      .thenReturn(IO(Option(currentParentResource)))
    when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(currentParentResource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))

    Delete(s"/api/resources/v2/${resourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
