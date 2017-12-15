package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  private val defaultResourceTypeActions = Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  private val defaultResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActions, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))
  private val otherResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActions, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))

  val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType), policyDAO, dirDAO, NoExtensions, "example.com")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  private val dummyUserInfo = UserInfo("token", WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchEmail("policy-randomuuid@example.com")
  }

  private def constructExpectedPolicies(resourceType: ResourceType, resource: Resource) = {
    val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).get
    val initialMembers = if(role.roleName.equals(resourceType.ownerRoleName)) Set(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]) else Set[WorkbenchSubject]()
    val group = BasicWorkbenchGroup(WorkbenchGroupName(role.roleName.value), initialMembers, toEmail(resource.resourceTypeName.value, resource.resourceId.value, role.roleName.value))
    Set(AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName(role.roleName.value)), group.members, group.email, Set(role.roleName), Set.empty))
  }

  "ResourceService" should "create and delete resource" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    runAndWait(service.createResourceType(defaultResourceType))

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    assertResult(constructExpectedPolicies(defaultResourceType, resource)) {
      runAndWait{policyDAO.listAccessPolicies(resource)}.map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))
    }

    //cleanup
    runAndWait(service.deleteResource(resource))

    assertResult(Set.empty) {
      runAndWait(policyDAO.listAccessPolicies(resource))
    }
  }

  "listUserResourceActions" should "list the user's actions for a resource" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName1 = ResourceId("resource1")
    val resourceName2 = ResourceId("resource2")

    try {
      runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

      runAndWait(service.createResourceType(defaultResourceType))
      runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo))

      val policies2 = runAndWait(service.createResource(defaultResourceType, resourceName2, dummyUserInfo))

      runAndWait(service.accessPolicyDAO.createPolicy(AccessPolicy(ResourceAndPolicyName(policies2, AccessPolicyName(otherRoleName.value)), Set(dummyUserInfo.userId), WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty)))

      assertResult(defaultResourceType.roles.filter(_.roleName.equals(ResourceRoleName("owner"))).head.actions) {
        runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName1), dummyUserInfo))
      }

      assertResult(defaultResourceTypeActions) {
        runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName2), dummyUserInfo))
      }

      assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName1), ResourceAction("non_owner_action"), dummyUserInfo)))
      assert(runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo)))
      assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, ResourceId("doesnotexist")), ResourceAction("view"), dummyUserInfo)))
    } finally {
      Try { runAndWait(service.deleteResource(Resource(defaultResourceType.name, resourceName1))) }
      Try { runAndWait(service.deleteResource(Resource(defaultResourceType.name, resourceName2))) }
      Try { runAndWait(dirDAO.deleteUser(dummyUserInfo.userId)) }
    }
  }

  it should "list the user's actions for a resource with nested groups" in {
    val resourceName1 = ResourceId("resource1")

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    val user = runAndWait(dirDAO.createUser(WorkbenchUser(WorkbenchUserId("asdfawefawea"), WorkbenchEmail("asdfawefawea@foo.bar"))))
    val group = BasicWorkbenchGroup(WorkbenchGroupName("g"), Set(user.id), WorkbenchEmail("foo@bar.com"))
    runAndWait(dirDAO.createGroup(group))

    runAndWait(service.createResourceType(defaultResourceType))
    val resource = runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo))
    val nonOwnerAction = ResourceAction("non_owner_action")
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("new_policy"), resource, AccessPolicyMembership(Set(group.email), Set(nonOwnerAction), Set.empty)))

    val userInfo = UserInfo("", user.id, user.email, 0)
    assertResult(Set(ResourceAction("non_owner_action"))) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName1), userInfo))
    }

    assert(runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName1), nonOwnerAction, userInfo)))
  }

  "createResource" should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("delete"), ResourceAction("view")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    try {
      runAndWait(service.createResourceType(resourceType))
      runAndWait(service.createResource(resourceType, resourceName, dummyUserInfo))

      val exception = intercept[WorkbenchExceptionWithErrorReport] {
        runAndWait(service.createResource(
          resourceType,
          resourceName,
          dummyUserInfo
        ))
      }

      exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)
    } finally {
      //cleanup
      Try { runAndWait(service.deleteResource(Resource(resourceType.name, resourceName))) }
    }
  }

  "listUserResourceRoles" should "list the user's role when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo))

    roles shouldEqual Set(ResourceRoleName("owner"))

    runAndWait(service.deleteResource(resource))
    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }

  it should "return an empty set when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    val roles = runAndWait(service.listUserResourceRoles(Resource(resourceType.name, resourceName), dummyUserInfo))

    roles shouldEqual Set.empty

    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }

  "listResourcePolicies" should "list policies for a newly created resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource)).map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)

    runAndWait(service.deleteResource(resource))
  }

  "overwritePolicy" should "succeed with a valid request" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")))

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty)))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource)).map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource))
  }

  it should "succeed with a regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("foo-.+-bar")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(rt))
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo))

    val actions = Set(ResourceAction("foo-bang-bar"))
    val newPolicy = runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, actions, Set.empty)))

    assertResult(actions) {
      newPolicy.actions
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource))
  }

  it should "fail when given an invalid action" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("INVALID_ACTION")))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty)))
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource))
  }

  it should "fail when given an invalid regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("foo-.+-bar")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(rt))
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo))

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("foo--bar")), Set.empty)))
    }
  }

  it should "fail when given an invalid role" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set(ResourceRoleName("INVALID_ROLE")), Set.empty)

    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE")))))
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))

    runAndWait(service.deleteResource(resource))
  }

  "deleteResource" should "delete the resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    assert(runAndWait(policyDAO.listAccessPolicies(resource)).nonEmpty)

    runAndWait(service.deleteResource(resource))

    assert(runAndWait(policyDAO.listAccessPolicies(resource)).isEmpty)
  }

  "listUserAccessPolicies" should "list user's access policies but not others" in {
    val resource1 = Resource(defaultResourceType.name, ResourceId("my-resource1"))
    val resource2 = Resource(defaultResourceType.name, ResourceId("my-resource2"))
    val resource3 = Resource(otherResourceType.name, ResourceId("my-resource1"))
    val resource4 = Resource(otherResourceType.name, ResourceId("my-resource2"))

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResourceType(otherResourceType))

    runAndWait(service.createResource(defaultResourceType, resource1.resourceId, dummyUserInfo))
    runAndWait(service.createResource(defaultResourceType, resource2.resourceId, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource3.resourceId, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource4.resourceId, dummyUserInfo))

    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("in-it"), resource1, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("not-in-it"), resource1, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(otherResourceType, AccessPolicyName("in-it"), resource3, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(otherResourceType, AccessPolicyName("not-in-it"), resource3, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty)))

    assertResult(Set(ResourceIdAndPolicyName(resource1.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value)), ResourceIdAndPolicyName(resource2.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value)), ResourceIdAndPolicyName(resource1.resourceId, AccessPolicyName("in-it")))) {
      runAndWait(service.listUserAccessPolicies(defaultResourceType, dummyUserInfo))
    }
  }

  "add/remove SubjectToPolicy" should "add/remove subject and tolerate prior (non)existence" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val policyName = AccessPolicyName(defaultResourceType.ownerRoleName.value)
    val otherUserInfo = UserInfo("token", WorkbenchUserId("otheruserid"), WorkbenchEmail("otheruser@company.com"), 0)

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(otherUserInfo.userId, otherUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    // assert baseline
    assertResult(Set.empty) {
      runAndWait(service.listUserAccessPolicies(defaultResourceType, otherUserInfo))
    }

    runAndWait(service.addSubjectToPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
    assertResult(Set(ResourceIdAndPolicyName(resource.resourceId, policyName))) {
      runAndWait(service.listUserAccessPolicies(defaultResourceType, otherUserInfo))
    }

    // add a second time to make sure no exception is thrown
    runAndWait(service.addSubjectToPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))


    runAndWait(service.removeSubjectFromPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
    assertResult(Set.empty) {
      runAndWait(service.listUserAccessPolicies(defaultResourceType, otherUserInfo))
    }

    // remove a second time to make sure no exception is thrown
    runAndWait(service.removeSubjectFromPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
  }
}
