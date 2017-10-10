package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  val service = new ResourceService(policyDAO, dirDAO, "example.com")

  override protected def beforeAll(): Unit = {
    runAndWait(schemaDao.init())
  }

  private val dummyUserInfo = UserInfo("token", WorkbenchUserId("userid"), WorkbenchUserEmail("user@company.com"), 0)

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchGroupEmail(s"policy-$resourceType-$resourceName-$policyName@example.com")
  }

  private val defaultResourceTypeActions = Set(ResourceAction("alterpolicies"), ResourceAction("delete"), ResourceAction("readpolicies"), ResourceAction("view"), ResourceAction("nonowneraction"))
  private val defaultResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActions, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("nonowneraction")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("nonowneraction")))), ResourceRoleName("owner"))

  private def constructExpectedPolicies(resourceType: ResourceType, resource: Resource) = {
    resourceType.roles.map { role =>
      val initialMembers = if(role.roleName.equals(resourceType.ownerRoleName)) Set(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]) else Set[WorkbenchSubject]()
      val group = WorkbenchGroup(WorkbenchGroupName(role.roleName.value), initialMembers, toEmail(resource.resourceTypeName.value, resource.resourceId.value, role.roleName.value))
      AccessPolicy(role.roleName.value, resource, group, Set(role.roleName), role.actions)
    }
  }

  "ResourceService" should "create and delete resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    runAndWait(service.createResourceType(defaultResourceType))

    val policies = runAndWait(service.createResource(
      defaultResourceType,
      resourceName,
      dummyUserInfo
    ))

    val ownerGroupName = WorkbenchGroupName(s"${defaultResourceType.name}-${resourceName.value}-owner")
    val otherGroupName = WorkbenchGroupName(s"${defaultResourceType.name}-${resourceName.value}-other")

    assertResult(constructExpectedPolicies(defaultResourceType, resource)) {
      policies
    }

    assertResult(policies) {
      runAndWait(policyDAO.listAccessPolicies(resource))
    }

    //cleanup
    runAndWait(service.deleteResource(resource, dummyUserInfo))

    assertResult(None) {
      runAndWait(dirDAO.loadGroup(ownerGroupName))
    }
    assertResult(None) {
      runAndWait(dirDAO.loadGroup(otherGroupName))
    }
    assertResult(Set.empty) {
      runAndWait(policyDAO.listAccessPolicies(resource))
    }
  }

  it should "listUserResourceActions" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceName1 = ResourceId("resource1")
    val resourceName2 = ResourceId("resource2")

    runAndWait(service.createResourceType(defaultResourceType))

    runAndWait(dirDAO.deleteUser(dummyUserInfo.userId))
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResource(
      defaultResourceType,
      resourceName1,
      dummyUserInfo
    ))
    val policies2 = runAndWait(service.createResource(
      defaultResourceType,
      resourceName2,
      dummyUserInfo
    ))

    policies2.filter(_.roles.contains(otherRoleName)).foreach { otherPolicy =>
      val members = otherPolicy.members.copy(members = Set(dummyUserInfo.userId))
      runAndWait(service.accessPolicyDAO.overwritePolicy(otherPolicy.copy(members = members)))
    }

    assertResult(defaultResourceType.roles.filter(_.roleName.equals(ResourceRoleName("owner"))).head.actions) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName1), dummyUserInfo))
    }

    assertResult(defaultResourceTypeActions) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName2), dummyUserInfo))
    }

    assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName1), ResourceAction("nonowneraction"), dummyUserInfo)))
    assert(runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName2), ResourceAction("nonowneraction"), dummyUserInfo)))
    assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, ResourceId("doesnotexist")), ResourceAction("view"), dummyUserInfo)))

    runAndWait(service.deleteResource(Resource(defaultResourceType.name, resourceName1), dummyUserInfo))
    runAndWait(service.deleteResource(Resource(defaultResourceType.name, resourceName2), dummyUserInfo))
    runAndWait(dirDAO.deleteUser(dummyUserInfo.userId))
  }

  it should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("delete"), ResourceAction("view")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    runAndWait(service.createResourceType(resourceType))

    runAndWait(service.createResource(
      resourceType,
      resourceName,
      dummyUserInfo
    ))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(
        resourceType,
        resourceName,
        dummyUserInfo
      ))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)

    //cleanup
    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName), dummyUserInfo))
  }

  it should "listUserResourceRoles when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    runAndWait(service.createResourceType(defaultResourceType))

    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResource(
      defaultResourceType,
      resourceName,
      dummyUserInfo
    ))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo))

    roles shouldEqual Set(ResourceRoleName("owner"))

    runAndWait(service.deleteResource(resource, dummyUserInfo))
    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }

  it should "return an empty set from listUserResourceRoles when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    val roles = runAndWait(service.listUserResourceRoles(Resource(resourceType.name, resourceName), dummyUserInfo))

    roles shouldEqual Set.empty

    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }

  it should "listResourcePolicies for a newly created resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)

    runAndWait(service.deleteResource(resource, dummyUserInfo))
  }

  it should "overwritePolicy with a valid request" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = WorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy("foo", resource, group, Set.empty, Set(ResourceAction("nonowneraction")))

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.name, newPolicy.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("nonowneraction")), Set.empty), dummyUserInfo))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource, dummyUserInfo))
  }

  it should "overwritePolicy should fail when given an invalid action" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = WorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy("foo", resource, group, Set.empty, Set(ResourceAction("INVALID_ACTION")))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.name, newPolicy.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty), dummyUserInfo))
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource, dummyUserInfo))
  }

  it should "overwritePolicy should fail when given an invalid role" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = WorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy("foo", resource, group, Set(ResourceRoleName("INVALID_ROLE")), Set.empty)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.name, newPolicy.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE"))), dummyUserInfo))
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource, dummyUserInfo))
  }

  it should "overwritePolicy should fail when user doesn't have alterpolicies permission" in {
    val resourceTypeWithNoAlter = defaultResourceType.copy(roles = Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("delete")))))

    val resource = Resource(resourceTypeWithNoAlter.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(resourceTypeWithNoAlter))
    runAndWait(service.createResource(resourceTypeWithNoAlter, resource.resourceId, dummyUserInfo))

    val group = WorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy("foo", resource, group, Set(ResourceRoleName("delete")), Set.empty)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(resourceTypeWithNoAlter, newPolicy.name, newPolicy.resource, AccessPolicyMembership(Set.empty, Set.empty, Set.empty), dummyUserInfo))
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource, dummyUserInfo))
  }

}
