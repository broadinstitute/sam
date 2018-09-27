package org.broadinstitute.dsde.workbench.sam
package service

import java.net.URI
import java.util.UUID

import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.Future

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, SchemaLockConfig, _}
import org.broadinstitute.dsde.workbench.sam.directory.LdapDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LdapAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import Generator._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.broadinstitute.dsde.workbench.sam.TestSupport.blockingEc
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with ScalaFutures with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val config = ConfigFactory.load()
  val directoryConfig = config.as[DirectoryConfig]("directory")
  val schemaLockConfig = config.as[SchemaLockConfig]("schemaLock")
  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val realResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
  private val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap

  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
  val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  private val defaultResourceTypeActions = Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  private val defaultResourceTypeActionPatterns = Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.delete, SamResourceActionPatterns.readPolicies, ResourceActionPattern("view", "", false), ResourceActionPattern("non_owner_action", "", false))
  private val defaultResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))
  private val otherResourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), defaultResourceTypeActionPatterns, Set(ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")), ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))), ResourceRoleName("owner"))

  private val constrainableActionPatterns = Set(ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true))
  private val constrainableViewAction = ResourceAction("constrainable_view")
  private val constrainableResourceTypeActions = Set(constrainableViewAction)
  private val constrainableReaderRoleName = ResourceRoleName("constrainable_reader")
  private val constrainableResourceType = ResourceType(
    genResourceTypeNameExcludeManagedGroup.sample.get,
    constrainableActionPatterns,
    Set(ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)),
    constrainableReaderRoleName
  )
  private val constrainablePolicyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(constrainableViewAction), Set(constrainableReaderRoleName))

  private val managedGroupResourceType = realResourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val emailDomain = "example.com"
  private val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType), policyDAO, dirDAO, NoExtensions, emailDomain)
  private val constrainableResourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
  private val constrainableService = new ResourceService(constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

  val managedGroupService = new ManagedGroupService(constrainableService, constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

  private object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)))
  }

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchEmail("policy-randomuuid@example.com")
  }

  private def constructExpectedPolicies(resourceType: ResourceType, resource: Resource) = {
    val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).get
    val initialMembers = if(role.roleName.equals(resourceType.ownerRoleName)) Set(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]) else Set[WorkbenchSubject]()
    val group = BasicWorkbenchGroup(WorkbenchGroupName(role.roleName.value), initialMembers, toEmail(resource.resourceTypeName.value, resource.resourceId.value, role.roleName.value))
    Set(AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName(role.roleName.value)), group.members, group.email, Set(role.roleName), Set.empty))
  }

  "ResourceType config" should "allow constraining policies to an auth domain" in {
    val resourceTypes = config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val rt = resourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val constrainedAction = rt.actionPatterns.find(_.value == "alter_policies").getOrElse(fail("Missing action pattern, please check src/test/resources/reference.conf"))
    constrainedAction.authDomainConstrainable shouldEqual true
  }

  // This test is here to support sam.conf generated by firecloud-develop
  it should "allow concatenation to ActionPatterns after they have been initialized" in {
    // In src/main/resources/reference.conf we define ResourceTypes and their permissible ActionPatterns
    // Then, in src/test/resources/reference.conf we concatenate value(s) to already defined objects and arrays
    // This mimics behavior from sam.conf, which is why we're testing here.
    val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val rt = resourceTypes.find(_.name == ResourceTypeName("billing-project")).getOrElse(fail("Missing resource type, please check src/main/resources/reference.conf"))
    rt.actionPatterns should contain (ResourceActionPattern("delete", "", false))
  }

  // https://broadinstitute.atlassian.net/browse/GAWB-3589
  it should "allow me to specify actionPatterns as Array[String] or Array[Object] until GAWB-3589 is resolved" in {
    val testResourceTypes = config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val realResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val testType = testResourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val billingProjectType = realResourceTypes.find(_.name == ResourceTypeName("billing-project")).getOrElse(fail("Missing billing-project resource type, please check src/main/resources/reference.conf"))
    // Note src/test/resources/reference.conf where we append either objects or strings to the actionPatterns fields for the two resourceTypes
    testType.actionPatterns should contain (ResourceActionPattern("foo", "this is to ensure that actionPatterns can be either Array[String] or Array[Object]", false))
    billingProjectType.actionPatterns should contain (ResourceActionPattern("delete", "", false))
  }

  "ResourceService" should "create and delete resource" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    assertResult(constructExpectedPolicies(defaultResourceType, resource)) {
      policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))
    }

    //cleanup
    runAndWait(service.deleteResource(resource))

    assertResult(Set.empty) {
      policyDAO.listAccessPolicies(resource).unsafeRunSync()
    }
  }

  "listUserResourceActions" should "list the user's actions for a resource" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName1 = ResourceId("resource1")
    val resourceName2 = ResourceId("resource2")

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo))

    val policies2 = runAndWait(service.createResource(defaultResourceType, resourceName2, dummyUserInfo))

    policyDAO.createPolicy(AccessPolicy(ResourceAndPolicyName(policies2, AccessPolicyName(otherRoleName.value)), Set(dummyUserInfo.userId), WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty)).unsafeRunSync()

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

    val user = runAndWait(dirDAO.createUser(WorkbenchUser(WorkbenchUserId("asdfawefawea"), None, WorkbenchEmail("asdfawefawea@foo.bar"))))
    val group = BasicWorkbenchGroup(WorkbenchGroupName("g"), Set(user.id), WorkbenchEmail("foo@bar.com"))
    runAndWait(dirDAO.createGroup(group))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    val resource = runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo))
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
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType).unsafeRunSync()
    runAndWait(service.createResource(resourceType, resourceName, dummyUserInfo))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(
        resourceType,
        resourceName,
        dummyUserInfo
      ))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)
  }

  it should "create resource with custom policies" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType).unsafeRunSync()

    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("view")), Set(ownerRoleName))
    val policyName = AccessPolicyName("foo")

    runAndWait(service.createResource(resourceType, resourceName, Map(policyName -> policyMembership), Set.empty, dummyUserInfo))

    val policies = runAndWait(service.listResourcePolicies(Resource(resourceType.name, resourceName)))
    assertResult(Set(AccessPolicyResponseEntry(policyName, policyMembership, WorkbenchEmail("")))) {
      policies.map(_.copy(email = WorkbenchEmail("")))
    }
  }

  it should "prevent ownerless resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType).unsafeRunSync()

    val exception1 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set.empty, Set.empty, Set(ownerRoleName))), Set.empty, dummyUserInfo))
    }

    exception1.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)

    val exception2 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set.empty)), Set.empty, dummyUserInfo))
    }

    exception2.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)
  }

  private def assertResourceExists(resource: Resource, resourceType: ResourceType, policyDao: AccessPolicyDAO) = {
    val resultingPolicies = policyDao.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email = WorkbenchEmail("policy-randomuuid@example.com")))
    resultingPolicies shouldEqual constructExpectedPolicies(resourceType, resource)
  }

  "Creating a resource that has at least 1 constrainable action pattern" should "succeed when no auth domain is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    val resource = runAndWait(service.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), dummyUserInfo))
    assertResourceExists(resource, constrainableResourceType, policyDAO)
  }

  it should "succeed when at least 1 valid auth domain group is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName = "fooGroup"
    val secondMGroupName = "barGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo))
    runAndWait(managedGroupService.createManagedGroup(ResourceId(secondMGroupName), dummyUserInfo))

    val authDomain = Set(WorkbenchGroupName(managedGroupName), WorkbenchGroupName(secondMGroupName))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, dummyUserInfo))
    val storedAuthDomain = runAndWait(constrainableService.loadResourceAuthDomain(resource))

    storedAuthDomain shouldEqual authDomain
  }

  it should "fail when at least 1 of the auth domain groups does not exist" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo))
    val nonExistentGroup = WorkbenchGroupName("aBadGroup")

    val authDomain = Set(WorkbenchGroupName(managedGroupName), nonExistentGroup)
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, dummyUserInfo))
    }
  }

  it should "fail when user does not have access to at least 1 of the auth domain groups" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    val bender = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("Bender"), WorkbenchEmail("bender@planex.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(bender.userId, None, bender.userEmail)))

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName1 = "firstGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName1), dummyUserInfo))
    val managedGroupName2 = "benderIsGreat"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName2), bender))

    val authDomain = Set(WorkbenchGroupName(managedGroupName1), WorkbenchGroupName(managedGroupName2))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, dummyUserInfo))
    }
  }

  "Loading an auth domain" should "fail when the resource does not exist" in {
    val e = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.loadResourceAuthDomain(Resource(constrainableResourceType.name, ResourceId(UUID.randomUUID().toString))))
    }

    e.getMessage should include ("not found")
  }

  "Creating a resource that has 0 constrainable action patterns" should "fail when an auth domain is provided" in {
    defaultResourceType.isAuthDomainConstrainable shouldEqual false
    service.createResourceType(defaultResourceType).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName = "fooGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo))

    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("view")), Set(ResourceRoleName("owner")))
    val policyName = AccessPolicyName("foo")

    val authDomain = Set(WorkbenchGroupName(managedGroupName))
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(defaultResourceType, ResourceId(UUID.randomUUID().toString), Map(policyName -> policyMembership), authDomain, dummyUserInfo))
    }
  }

  "listUserResourceRoles" should "list the user's role when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = Resource(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo))

    roles shouldEqual Set(ResourceRoleName("owner"))
  }

  it should "return an empty set when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("a1", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    val roles = runAndWait(service.listUserResourceRoles(Resource(resourceType.name, resourceName), dummyUserInfo))

    roles shouldEqual Set.empty

    runAndWait(dirDAO.deleteUser(dummyUserInfo.userId))
  }

  "policyDao.listAccessPolicies" should "list policies for a newly created resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)
  }

  "listResourcePolicies" should "list policies for a newly created resource without member email addresses if the User does not exist" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName)), forcedEmail)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = runAndWait(service.listResourcePolicies(resource)).map(_.copy(email = forcedEmail))
    policies shouldEqual Set(expectedPolicy)
  }

  it should "list policies for a newly created resource with the member email addresses if the User has been added" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName)), forcedEmail)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = runAndWait(service.listResourcePolicies(resource)).map(_.copy(email = forcedEmail))
    policies shouldEqual Set(expectedPolicy)
  }

  "overwritePolicy" should "succeed with a valid request" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")))

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty)))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  it should "succeed with a regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    service.createResourceType(rt).unsafeRunSync()
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo))

    val actions = Set(ResourceAction("foo-bang-bar"))
    val newPolicy = runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, actions, Set.empty)))

    assertResult(actions) {
      newPolicy.actions
    }

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(policies.contains(newPolicy))
  }

  it should "fail when given an invalid action" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("INVALID_ACTION")))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner"))
    val resource = Resource(rt.name, ResourceId("my-resource"))

    service.createResourceType(rt).unsafeRunSync()
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("foo--bar")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))
  }

  it should "fail when given an invalid role" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set(ResourceRoleName("INVALID_ROLE")), Set.empty)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE")))))
    }

    assert(exception.getMessage.contains("invalid role"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid member email" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set(WorkbenchEmail("null@null.com")), Set.empty, Set.empty)))
    }

    assert(exception.getMessage.contains("invalid member email"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  "deleteResource" should "delete the resource" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    assert(policyDAO.listAccessPolicies(resource).unsafeRunSync().nonEmpty)

    runAndWait(service.deleteResource(resource))

    assert(policyDAO.listAccessPolicies(resource).unsafeRunSync().isEmpty)
  }

  it should "not allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is false for the Resource Type" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))

    defaultResourceType.reuseIds shouldEqual false

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))
    runAndWait(service.deleteResource(resource))

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))
    }
    err.getMessage should include ("resource of this type and name already exists")
  }

  it should "allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is true for the Resource Type" in {
    val reusableResourceType = defaultResourceType.copy(reuseIds = true)
    reusableResourceType.reuseIds shouldEqual true
    val localService = new ResourceService(Map(reusableResourceType.name -> reusableResourceType), policyDAO, dirDAO, NoExtensions, "example.com")

    localService.createResourceType(reusableResourceType).unsafeRunSync()

    val resource = Resource(reusableResourceType.name, ResourceId("my-resource"))

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() should not be empty

    runAndWait(localService.deleteResource(resource))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() shouldBe empty

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() should not be empty
  }

  "listUserAccessPolicies" should "list user's access policies but not others" in {
    val resource1 = Resource(defaultResourceType.name, ResourceId("my-resource1"))
    val resource2 = Resource(defaultResourceType.name, ResourceId("my-resource2"))
    val resource3 = Resource(otherResourceType.name, ResourceId("my-resource1"))
    val resource4 = Resource(otherResourceType.name, ResourceId("my-resource2"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    service.createResourceType(otherResourceType).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resource1.resourceId, dummyUserInfo))
    runAndWait(service.createResource(defaultResourceType, resource2.resourceId, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource3.resourceId, dummyUserInfo))
    runAndWait(service.createResource(otherResourceType, resource4.resourceId, dummyUserInfo))

    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("in-it"), resource1, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("not-in-it"), resource1, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(otherResourceType, AccessPolicyName("in-it"), resource3, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)))
    runAndWait(service.overwritePolicy(otherResourceType, AccessPolicyName("not-in-it"), resource3, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty)))

    assertResult(Set(
      UserPolicyResponse(resource1.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value), Set.empty, Set.empty),
      UserPolicyResponse(resource2.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value), Set.empty, Set.empty),
      UserPolicyResponse(resource1.resourceId, AccessPolicyName("in-it"), Set.empty, Set.empty))) {
      service.listUserAccessPolicies(defaultResourceType.name, dummyUserInfo.userId).unsafeRunSync()
    }
  }

  it should "list required authDomains if constrainable" in {
    val policy = (AccessPolicy.id composeLens ResourceAndPolicyName.resource composeLens Resource.resourceTypeName).set(constrainableResourceType.name)(genPolicy.sample.get)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService.createResourceType(managedGroupResourceType).unsafeToFuture()  // make sure managed groups in auth domain set are created. dummyUserInfo will be member of the created resourceId
      _ <- Future.traverse(resource.authDomain)(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo)
      r <- constrainableService.listUserAccessPolicies(constrainableResourceType.name, dummyUserInfo.userId).unsafeToFuture()
    } yield r

    val expected = Set(UserPolicyResponse(resource.resourceId, viewPolicyName, resource.authDomain, Set.empty))
    res.futureValue(Timeout(Span(10, Seconds))) shouldBe expected
  }

  it should "list required authDomains and authDomains user is not a member of if constrainable" in {
    val user = genUserInfo.sample.get
    val policy = (AccessPolicy.id composeLens ResourceAndPolicyName.resource composeLens Resource.resourceTypeName).set(constrainableResourceType.name)(genPolicy.sample.get)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService.createResourceType(managedGroupResourceType).unsafeToFuture()
      _ <- Future.traverse(resource.authDomain)(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(GoogleSubjectId(user.userId.value)), user.userEmail))
      _ <- constrainableService.createPolicy(policy.id, policy.members + user.userId, policy.roles, policy.actions)
      r <- constrainableService.listUserAccessPolicies(constrainableResourceType.name, user.userId).unsafeToFuture()
    } yield r

    val expected = Set(UserPolicyResponse(resource.resourceId, policy.id.accessPolicyName, resource.authDomain, resource.authDomain))
    res.futureValue(Timeout(Span(10, Seconds))) shouldBe expected
  }

  "add/remove SubjectToPolicy" should "add/remove subject and tolerate prior (non)existence" in {
    val resource = Resource(defaultResourceType.name, ResourceId("my-resource"))
    val policyName = AccessPolicyName(defaultResourceType.ownerRoleName.value)
    val otherUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("otheruserid"), WorkbenchEmail("otheruser@company.com"), 0)

    runAndWait(dirDAO.createUser(WorkbenchUser(otherUserInfo.userId, None, otherUserInfo.userEmail)))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    // assert baseline
    assertResult(Set.empty) {
      service.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    runAndWait(service.addSubjectToPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
    assertResult(Set(UserPolicyResponse(resource.resourceId, policyName, Set.empty, Set.empty))) {
      service.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    // add a second time to make sure no exception is thrown
    runAndWait(service.addSubjectToPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))


    runAndWait(service.removeSubjectFromPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
    assertResult(Set.empty) {
      service.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    // remove a second time to make sure no exception is thrown
    runAndWait(service.removeSubjectFromPolicy(ResourceAndPolicyName(resource, policyName), otherUserInfo.userId))
  }
}
