package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
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
  private val defaultResourceTypeActionPatterns = Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.delete, SamResourceActionPatterns.readPolicies, ResourceActionPattern("view"), ResourceActionPattern("non_owner_action"))
  private val defaultResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))
  private val otherResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))

  val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType), policyDAO, dirDAO, NoExtensions, "example.com")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

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

    runAndWait(service.createResource(defaultResourceType, resourceName, None, dummyUserInfo))

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

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resourceName1, None, dummyUserInfo))

    val policies2 = runAndWait(service.createResource(defaultResourceType, resourceName2, None, dummyUserInfo))

    runAndWait(policyDAO.createPolicy(AccessPolicy(ResourceAndPolicyName(policies2, AccessPolicyName(otherRoleName.value)), Set(dummyUserInfo.userId), WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty)))

    assertResult(defaultResourceType.roles.filter(_.roleName.equals(ResourceRoleName("owner"))).head.actions) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName1), dummyUserInfo))
    }

    assertResult(defaultResourceTypeActions) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName2), dummyUserInfo))
    }

    assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName1), ResourceAction("non_owner_action"), dummyUserInfo)))
    assert(runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo)))
    assert(!runAndWait(service.hasPermission(Resource(defaultResourceType.name, ResourceId("doesnotexist")), ResourceAction("view"), dummyUserInfo)))
  }

  it should "list the user's actions for a resource with nested groups" in {
    val resourceName1 = ResourceId("resource1")

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    val user = runAndWait(dirDAO.createUser(WorkbenchUser(WorkbenchUserId("asdfawefawea"), WorkbenchEmail("asdfawefawea@foo.bar"))))
    val group = BasicWorkbenchGroup(WorkbenchGroupName("g"), Set(user.id), WorkbenchEmail("foo@bar.com"))
    runAndWait(dirDAO.createGroup(group))

    runAndWait(service.createResourceType(defaultResourceType))
    val resource = runAndWait(service.createResource(defaultResourceType, resourceName1, None, dummyUserInfo))
    val nonOwnerAction = ResourceAction("non_owner_action")
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("new_policy"), resource, AccessPolicyMembership(Set(group.email), Set(nonOwnerAction), Set.empty)))

    val userInfo = UserInfo(OAuth2BearerToken(""), user.id, user.email, 0)
    assertResult(Set(ResourceAction("non_owner_action"))) {
      runAndWait(service.listUserResourceActions(Resource(defaultResourceType.name, resourceName1), userInfo))
    }

    assert(runAndWait(service.hasPermission(Resource(defaultResourceType.name, resourceName1), nonOwnerAction, userInfo)))
  }

  "createResource" should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    runAndWait(service.createResourceType(resourceType))
    runAndWait(service.createResource(resourceType, resourceName, None, dummyUserInfo))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(
        resourceType,
        resourceName,
        None,
        dummyUserInfo
      ))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)
  }

  "listUserResourceRoles" should "list the user's role when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resourceName, None, dummyUserInfo))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo))

    roles shouldEqual Set(ResourceRoleName("owner"))
  }

  it should "return an empty set when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("a1")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    val roles = runAndWait(service.listUserResourceRoles(Resource(resourceType.name, resourceName), dummyUserInfo))

    roles shouldEqual Set.empty

    runAndWait(dirDAO.deleteUser(dummyUserInfo.userId))
  }

  "policyDao.listAccessPolicies" should "list policies for a newly created resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource)).map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)
  }

  "listResourcePolicies" should "list policies for a newly created resource without member email addresses if the User does not exist" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set.empty, Set.empty, Set(ownerRole.roleName)), forcedEmail)

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val policies = runAndWait(service.listResourcePolicies(resource)).map(_.copy(email = forcedEmail))
    policies shouldEqual Set(expectedPolicy)
  }

  "listResourcePolicies" should "list policies for a newly created resource with the member email addresses if the User has been added" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName)), forcedEmail)

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    val policies = runAndWait(service.listResourcePolicies(resource)).map(_.copy(email = forcedEmail))
    policies shouldEqual Set(expectedPolicy)
  }

  "overwritePolicy" should "succeed with a valid request" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")))

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty)))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource)).map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  it should "succeed with a regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(rt))
    runAndWait(service.createResource(rt, resource.resourceId, None, dummyUserInfo))

    val actions = Set(ResourceAction("foo-bang-bar"))
    val newPolicy = runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, actions, Set.empty)))

    assertResult(actions) {
      newPolicy.actions
    }

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(policies.contains(newPolicy))
  }

  it should "fail when given an invalid action" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("INVALID_ACTION")))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(rt))
    runAndWait(service.createResource(rt, resource.resourceId, None, dummyUserInfo))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("foo--bar")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))
  }

  it should "fail when given an invalid role" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set(ResourceRoleName("INVALID_ROLE")), Set.empty)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE")))))
    }

    assert(exception.getMessage.contains("invalid role"))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid member email" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")))
    
    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set(WorkbenchEmail("null@null.com")), Set.empty, Set.empty)))
    }

    assert(exception.getMessage.contains("invalid member email"))

    val policies = runAndWait(policyDAO.listAccessPolicies(resource))

    assert(!policies.contains(newPolicy))
  }

  "deleteResource" should "delete the resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

    assert(runAndWait(policyDAO.listAccessPolicies(resource)).nonEmpty)

    runAndWait(service.deleteResource(resource))

    assert(runAndWait(policyDAO.listAccessPolicies(resource)).isEmpty)
  }

  it should "not allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is false for the Resource Type" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    defaultResourceType.reuseIds shouldEqual false

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))
    runAndWait(service.deleteResource(resource))

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))
    }
    err.getMessage should include ("resource of this type and name already exists")
  }

  it should "allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is true for the Resource Type" in {
    val reusableResourceType = defaultResourceType.copy(reuseIds = true)
    reusableResourceType.reuseIds shouldEqual true
    val localService = new ResourceService(Map(reusableResourceType.name -> reusableResourceType), policyDAO, dirDAO, NoExtensions, "example.com")

    runAndWait(localService.createResourceType(reusableResourceType))

    val resource = Resource(reusableResourceType.name, ResourceId("my-resource"))

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, None, dummyUserInfo))
    runAndWait(policyDAO.listAccessPolicies(resource)) should not be empty

    runAndWait(localService.deleteResource(resource))
    runAndWait(policyDAO.listAccessPolicies(resource)) shouldBe empty

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, None, dummyUserInfo))
    runAndWait(policyDAO.listAccessPolicies(resource)) should not be empty
  }

  "listUserAccessPolicies" should "list user's access policies but not others" in {
    val resource1 = Resource(defaultResourceType.name, ResourceId("my-resource1"))
    val resource2 = Resource(defaultResourceType.name, ResourceId("my-resource2"))
    val resource3 = Resource(otherResourceType.name, ResourceId("my-resource1"))
    val resource4 = Resource(otherResourceType.name, ResourceId("my-resource2"))

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResourceType(otherResourceType))

    runAndWait(service.createResource(defaultResourceType, resource1.resourceId, None, dummyUserInfo))
    runAndWait(service.createResource(defaultResourceType, resource2.resourceId, None, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource3.resourceId, None, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource4.resourceId, None, dummyUserInfo))

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
    val otherUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("otheruserid"), WorkbenchEmail("otheruser@company.com"), 0)

    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
    runAndWait(dirDAO.createUser(WorkbenchUser(otherUserInfo.userId, otherUserInfo.userEmail)))

    runAndWait(service.createResourceType(defaultResourceType))
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, None, dummyUserInfo))

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
