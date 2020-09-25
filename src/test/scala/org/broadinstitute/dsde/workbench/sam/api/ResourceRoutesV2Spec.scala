package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.genGoogleSubjectId
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{AppendedClues, FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future


class ResourceRoutesV2Spec extends FlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {

  implicit val errorReportSource = ErrorReportSource("sam")

  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)

  val defaultResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
    ResourceRoleName("owner")
  )

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType] = Map(defaultResourceType.name -> defaultResourceType),
                              userInfo: UserInfo = defaultUserInfo): SamRoutes = {
    val accessPolicyDAO = new MockAccessPolicyDAO()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()
    val emailDomain = "example.com"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val mockResourceService = mock[ResourceService](RETURNS_SMART_NULLS)
    resourceTypes.map { case (resourceTypeName, resourceType) =>
      when(mockResourceService.getResourceType(resourceTypeName)).thenReturn(IO(Option(resourceType)))
    }
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, NoExtensions, emailDomain)

    mockUserService.createUser(CreateWorkbenchUser(defaultUserInfo.userId, genGoogleSubjectId(), defaultUserInfo.userEmail, None), samRequestContext)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }

  private def mockPermissionsForResource(samRoutes: SamRoutes,
                                         resource: FullyQualifiedResourceId,
                                         actionsOnResource: Set[ResourceAction] = Set.empty,
                                         missingActionsOnResource: Set[ResourceAction] = Set.empty,
                                         accessToResource: Boolean = true): Unit = {
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(resource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)
    actionsOnResource.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(resource), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(true)))
    missingActionsOnResource.map(action => when(samRoutes.policyEvaluatorService.hasPermission(mockitoEq(resource), mockitoEq(action), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
      .thenReturn(IO(false)))

    if (accessToResource) {
      when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(resource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
        .thenReturn(IO(Set(otherPolicy)))
    } else {
      when(samRoutes.policyEvaluatorService.listResourceAccessPoliciesForUser(mockitoEq(resource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext]))
        .thenReturn(IO(Set[AccessPolicyWithoutMembers]()))
    }
  }

  // mock out a bunch of calls in ResourceService and PolicyEvaluatorService to reduce bloat in /parent tests
  private def setupParentRoutes(samRoutes: SamRoutes,
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
    // mock responses for child resource
    mockPermissionsForResource(samRoutes, childResource,
      actionsOnResource = actionsOnChild,
      missingActionsOnResource = missingActionsOnChild,
      accessToResource = accessToChild)

    // mock responses for current parent resource
    currentParentOpt match {
      case Some(currentParent) =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(Option(currentParent)))
        when(samRoutes.resourceService.deleteResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO.pure(true))
        mockPermissionsForResource(samRoutes, currentParent,
          actionsOnResource = actionsOnCurrentParent,
          missingActionsOnResource = missingActionsOnCurrentParent,
          accessToResource = accessToCurrentParent)
      case None =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(None))
        when(samRoutes.resourceService.deleteResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO.pure(false))
    }

    // mock responses for new parent resource
    newParentOpt.map { newParent =>
      when(samRoutes.resourceService.setResourceParent(mockitoEq(childResource), mockitoEq(newParent), any[SamRequestContext]))
        .thenReturn(IO.unit)
      mockPermissionsForResource(samRoutes, newParent,
        actionsOnResource = actionsOnNewParent,
        missingActionsOnResource = missingActionsOnNewParent,
        accessToResource = accessToNewParent)
    }

    if (accessToChild && accessToCurrentParent) {
      when(samRoutes.resourceService.deleteResource(mockitoEq(childResource), any[SamRequestContext])).thenReturn(Future.unit)
    }
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "200 if user has get_parent on resource and resource has parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(fullyQualifiedParentResource),
      actionsOnChild = Set(SamResourceActions.getParent))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[FullyQualifiedResourceId] shouldEqual fullyQualifiedParentResource
    }
  }

  it should "403 if user is missing get_parent on resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, currentParentOpt = Option(fullyQualifiedParentResource),
      missingActionsOnChild = Set(SamResourceActions.getParent))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, None, actionsOnChild = Set(SamResourceActions.getParent))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      missingActionsOnChild = Set(SamResourceActions.getParent),
      accessToChild = false)

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success when there is not a parent already set" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, newParentOpt = Option(fullyQualifiedParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", fullyQualifiedParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 on success when there is a parent already set" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing set_parent on child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      missingActionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing add_child on new parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      missingActionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      missingActionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      missingActionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild),
      accessToChild = false)

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 if user doesn't have access to new parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      missingActionsOnNewParent = Set(SamResourceActions.addChild),
      accessToNewParent = false)

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if the new parent resource does not exist" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val nonexistentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("nonexistentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = None,
      newParentOpt = Option(nonexistentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      missingActionsOnNewParent = Set(SamResourceActions.addChild)
    )

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", nonexistentParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user doesn't have access to existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      missingActionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild),
      accessToCurrentParent = false)

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing set_parent on child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      missingActionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on parent resource if it exists" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      missingActionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      actionsOnChild = Set(SamResourceActions.setParent))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      missingActionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      accessToChild = false)

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 if user doesn't have access to existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      missingActionsOnCurrentParent = Set(SamResourceActions.removeChild),
      accessToCurrentParent = false)

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/children" should "200 with list of children FullyQualifiedResourceIds on success" in {
    val child1 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child1"))
    val child2 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child2"))
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, parent, actionsOnResource = Set(SamResourceActions.listChildren))

    when(samRoutes.resourceService.listResourceChildren(mockitoEq(parent), any[SamRequestContext]))
      .thenReturn(IO(Set(child1, child2)))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[FullyQualifiedResourceId]] shouldEqual Set(child1, child2)
    }
  }

  it should "403 if user is missing list_children on the parent resource" in {
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(parent, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, parent, missingActionsOnResource = Set(SamResourceActions.listChildren))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to parent resource" in {
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, parent,
      missingActionsOnResource = Set(SamResourceActions.listChildren),
      accessToResource = false)

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}/policies/{policyName}" should "204 on success" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyToDelete.accessPolicyName)))
    when(samRoutes.resourceService.deletePolicy(mockitoEq(policyToDelete), any[SamRequestContext]))
      .thenReturn(IO.unit)

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing both alter_policies and delete_policy on the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(resource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyToDelete.accessPolicyName)))

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyToDelete.accessPolicyName)),
      accessToResource = false)

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v1/{resourceTypeName}/{resourceId}" should "204 on a child resource if the user has remove_child on the parent resource" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    //Delete the resource
    Delete(s"/api/resources/v1/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing remove_child on parent resource if it exists" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      missingActionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v1/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}" should "200 on existing policy of a resource with read_policies" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set.empty, None)
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    // mock response to load policy
    when(samRoutes.resourceService.loadResourcePolicy(mockitoEq(FullyQualifiedPolicyId(resource, policyName)), any[SamRequestContext]))
      .thenReturn(IO(Option(members)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  it should "200 on existing policy if user can read just that policy" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set.empty, None)
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicy(policyName)),
      missingActionsOnResource = Set(SamResourceActions.readPolicies))

    // mock response to load policy
    when(samRoutes.resourceService.loadResourcePolicy(mockitoEq(FullyQualifiedPolicyId(resource, policyName)), any[SamRequestContext]))
      .thenReturn(IO(Option(members)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  it should "403 on existing policy of a resource without read policies" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyName)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "400 when attempting to delete a resource with children" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val parentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(parentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    //Throw 400 exception when delete is called
    when(samRoutes.resourceService.deleteResource(mockitoEq(childResource), any[SamRequestContext]))
      .thenThrow(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot delete a resource with children. Delete the children first then try again.")))

    //Delete the resource
    Delete(s"/api/resources/v1/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "404 on non existing policy of a resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.readPolicies, SamResourceActions.readPolicy(policyName)),
      accessToResource = false)

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}" should "201 on a new policy being created for a resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val policy = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members.roles, members.actions, members.getDescendantPermissions, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO(policy))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "201 on a policy being updated" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val policy = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members.roles, members.actions, members.getDescendantPermissions, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO(policy))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // update existing policy
    val members2 = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute"), ResourceAction("new_action")), Set.empty, None)
    val policy2 = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members2.roles, members2.actions, members2.getDescendantPermissions, false)
    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members2), any[SamRequestContext]))
      .thenReturn(IO(policy2))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members2) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "400 when creating an invalid policy" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy"))))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 when creating a policy on a resource when the user doesn't have alter_policies permission (but can see the resource)" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.alterPolicies))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when creating a policy on a resource that the user doesnt have permission to see" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.alterPolicies),
      accessToResource = false)

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/policies" should "200 when listing policies for a resource and user has read_policies permission" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(Stream(response)))

    Get(s"/api/resources/v1/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "403 when listing policies for a resource and user lacks read_policies permission (but can see the resource)" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.readPolicies))

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(Stream(response)))

    Get(s"/api/resources/v1/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when listing policies for a resource when user can't see the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      missingActionsOnResource = Set(SamResourceActions.readPolicies),
      accessToResource = false)

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(Stream(response)))

    Get(s"/api/resources/v1/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
