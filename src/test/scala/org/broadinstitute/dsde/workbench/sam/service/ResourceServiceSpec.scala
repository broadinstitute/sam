package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.data.NonEmptyList
import cats.effect.IO
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.blockingEc
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.resourceTypeReader
import org.broadinstitute.dsde.workbench.sam.directory.LdapDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LdapAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with ScalaFutures with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig = TestSupport.directoryConfig
  val schemaLockConfig = TestSupport.schemaLockConfig
  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val realResourceTypes = TestSupport.appConfig.resourceTypes
  private val realResourceTypeMap = realResourceTypes.map(rt => rt.name -> rt).toMap

  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig, blockingEc, TestSupport.testMemberOfCache)
  val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc, TestSupport.testMemberOfCache, TestSupport.testResourceCache)
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
  private val policyEvaluatorService = PolicyEvaluatorService(emailDomain, Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType, managedGroupResourceType.name -> managedGroupResourceType), policyDAO)
  private val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType, managedGroupResourceType.name -> managedGroupResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)
  private val constrainableResourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
  private val constrainablePolicyEvaluatorService = PolicyEvaluatorService(emailDomain, constrainableResourceTypes, policyDAO)
  private val constrainableService = new ResourceService(constrainableResourceTypes, constrainablePolicyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

  val managedGroupService = new ManagedGroupService(constrainableService, constrainablePolicyEvaluatorService, constrainableResourceTypes, policyDAO, dirDAO, NoExtensions, emailDomain)

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
    dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail)).unsafeRunSync()
  }

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchEmail("policy-randomuuid@example.com")
  }

  private def constructExpectedPolicies(resourceType: ResourceType, resource: FullyQualifiedResourceId) = {
    val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).get
    val initialMembers = if(role.roleName.equals(resourceType.ownerRoleName)) Set(dummyUserInfo.userId.asInstanceOf[WorkbenchSubject]) else Set[WorkbenchSubject]()
    val group = BasicWorkbenchGroup(WorkbenchGroupName(role.roleName.value), initialMembers, toEmail(resource.resourceTypeName.value, resource.resourceId.value, role.roleName.value))
    Stream(AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName(role.roleName.value)), group.members, group.email, Set(role.roleName), Set.empty, public = false))
  }

  "ResourceType config" should "allow constraining policies to an auth domain" in {
    val resourceTypes = TestSupport.config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val rt = resourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val constrainedAction = rt.actionPatterns.find(_.value == "alter_policies").getOrElse(fail("Missing action pattern, please check src/test/resources/reference.conf"))
    constrainedAction.authDomainConstrainable shouldEqual true
  }

  // This test is here to support sam.conf generated by firecloud-develop
  it should "allow concatenation to ActionPatterns after they have been initialized" in {
    // In src/main/resources/reference.conf we define ResourceTypes and their permissible ActionPatterns
    // Then, in src/test/resources/reference.conf we concatenate value(s) to already defined objects and arrays
    // This mimics behavior from sam.conf, which is why we're testing here.
    val resourceTypes = TestSupport.appConfig.resourceTypes
    val rt = resourceTypes.find(_.name == ResourceTypeName("billing-project")).getOrElse(fail("Missing resource type, please check src/main/resources/reference.conf"))
    rt.actionPatterns should contain (ResourceActionPattern("delete", "", false))
  }

  // https://broadinstitute.atlassian.net/browse/GAWB-3589
  it should "allow me to specify actionPatterns as Array[String] or Array[Object] until GAWB-3589 is resolved" in {
    val testResourceTypes = TestSupport.config.as[Map[String, ResourceType]]("testStuff.resourceTypes").values.toSet
    val realResourceTypes = TestSupport.config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val testType = testResourceTypes.find(_.name == ResourceTypeName("testType")).getOrElse(fail("Missing test resource type, please check src/test/resources/reference.conf"))
    val billingProjectType = realResourceTypes.find(_.name == ResourceTypeName("billing-project")).getOrElse(fail("Missing billing-project resource type, please check src/main/resources/reference.conf"))
    // Note src/test/resources/reference.conf where we append either objects or strings to the actionPatterns fields for the two resourceTypes
    testType.actionPatterns should contain (ResourceActionPattern("foo", "this is to ensure that actionPatterns can be either Array[String] or Array[Object]", false))
    billingProjectType.actionPatterns should contain (ResourceActionPattern("delete", "", false))
  }

  "ResourceService" should "create and delete resource" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    assertResult(constructExpectedPolicies(defaultResourceType, resource)) {
      policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))
    }

    //cleanup
    runAndWait(service.deleteResource(resource))

    assertResult(Stream.empty) {
      policyDAO.listAccessPolicies(resource).unsafeRunSync()
    }
  }

  it should "set public policies" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType).unsafeRunSync()

    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    val policyToUpdate = constructExpectedPolicies(defaultResourceType, resource).head.id

    service.isPublic(policyToUpdate).unsafeRunSync() should equal(false)

    service.setPublic(policyToUpdate, true).unsafeRunSync()
    service.isPublic(policyToUpdate).unsafeRunSync() should equal(true)

    service.setPublic(policyToUpdate, false).unsafeRunSync()
    service.isPublic(policyToUpdate).unsafeRunSync() should equal(false)

    //cleanup
    runAndWait(service.deleteResource(resource))
  }

  it should "fail to set public policies on auth domained resources" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()

    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(constrainableResourceType.name, resourceName)

    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()
    val managedGroupResource = managedGroupService.createManagedGroup(ResourceId("ad"), dummyUserInfo).futureValue

    val ownerRoleName = constrainableReaderRoleName
    val policyMembership = AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(constrainableViewAction), Set(ownerRoleName))
    val policyName = AccessPolicyName("foo")

    val testResource = runAndWait(constrainableService.createResource(constrainableResourceType, resourceName, Map(policyName -> policyMembership), Set(WorkbenchGroupName(managedGroupResource.resourceId.value)), dummyUserInfo.userId))

    val policyToUpdate = FullyQualifiedPolicyId(testResource.fullyQualifiedId, policyName)
    constrainableService.isPublic(policyToUpdate).unsafeRunSync() should equal(false)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      constrainableService.setPublic(policyToUpdate, true).unsafeRunSync()
    }

    err.errorReport.statusCode should equal(Some(StatusCodes.BadRequest))
  }

  "listUserResourceActions" should "list the user's actions for a resource" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName1 = ResourceId("resource1")
    val resourceName2 = ResourceId("resource2")

    service.createResourceType(defaultResourceType).unsafeRunSync()
    service.createResource(defaultResourceType, resourceName1, dummyUserInfo).futureValue

    val policies2 = service.createResource(defaultResourceType, resourceName2, dummyUserInfo).futureValue

    policyDAO.createPolicy(AccessPolicy(
      FullyQualifiedPolicyId(policies2.fullyQualifiedId, AccessPolicyName(otherRoleName.value)), Set(dummyUserInfo.userId), WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty, public = false)).unsafeRunSync()

    assertResult(defaultResourceType.roles.filter(_.roleName.equals(ResourceRoleName("owner"))).head.actions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), dummyUserInfo.userId).unsafeRunSync()
    }

    assertResult(defaultResourceTypeActions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), dummyUserInfo.userId).unsafeRunSync()
    }

    assert(!service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), ResourceAction("non_owner_action"), dummyUserInfo.userId).unsafeRunSync())
    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo.userId).unsafeRunSync())
    assert(!service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, ResourceId("doesnotexist")), ResourceAction("view"), dummyUserInfo.userId).unsafeRunSync())
  }

  it should "list the user's actions for a resource with nested groups" in {
    val resourceName1 = ResourceId("resource1")

    val user = dirDAO.createUser(WorkbenchUser(WorkbenchUserId("asdfawefawea"), None, WorkbenchEmail("asdfawefawea@foo.bar"))).unsafeRunSync()
    val group = BasicWorkbenchGroup(WorkbenchGroupName("g"), Set(user.id), WorkbenchEmail("foo@bar.com"))
    dirDAO.createGroup(group).unsafeRunSync()

    service.createResourceType(defaultResourceType).unsafeRunSync()
    val resource = runAndWait(service.createResource(defaultResourceType, resourceName1, dummyUserInfo))
    val nonOwnerAction = ResourceAction("non_owner_action")
    runAndWait(service.overwritePolicy(defaultResourceType, AccessPolicyName("new_policy"), resource.fullyQualifiedId, AccessPolicyMembership(Set(group.email), Set(nonOwnerAction), Set.empty)))

    val userInfo = UserInfo(OAuth2BearerToken(""), user.id, user.email, 0)
    assertResult(Set(ResourceAction("non_owner_action"))) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), userInfo.userId).unsafeRunSync()
    }

    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName1), nonOwnerAction, userInfo.userId).unsafeRunSync())
  }

  it should "list public policies" in {
    val otherRoleName = ResourceRoleName("other")
    val resourceName2 = ResourceId("resource2")

    service.createResourceType(defaultResourceType).unsafeRunSync()

    val policies2 = runAndWait(service.createResource(defaultResourceType, resourceName2, dummyUserInfo))

    policyDAO.createPolicy(AccessPolicy(
      FullyQualifiedPolicyId(policies2.fullyQualifiedId, AccessPolicyName(otherRoleName.value)), Set.empty, WorkbenchEmail("a@b.c"), Set(otherRoleName), Set.empty, public = true)).unsafeRunSync()

    assertResult(defaultResourceTypeActions) {
      service.policyEvaluatorService.listUserResourceActions(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), dummyUserInfo.userId).unsafeRunSync()
    }

    assert(service.policyEvaluatorService.hasPermission(FullyQualifiedResourceId(defaultResourceType.name, resourceName2), ResourceAction("non_owner_action"), dummyUserInfo.userId).unsafeRunSync())
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

    runAndWait(service.createResource(resourceType, resourceName, Map(policyName -> policyMembership), Set.empty, dummyUserInfo.userId))

    val policies = service.listResourcePolicies(FullyQualifiedResourceId(resourceType.name, resourceName)).unsafeRunSync()
    assertResult(Stream(AccessPolicyResponseEntry(policyName, policyMembership, WorkbenchEmail("")))) {
      policies.map(_.copy(email = WorkbenchEmail("")))
    }
  }

  it should "prevent ownerless resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(SamResourceActionPatterns.delete, ResourceActionPattern("view", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("delete"), ResourceAction("view")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    service.createResourceType(resourceType).unsafeRunSync()

    val exception1 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set.empty, Set.empty, Set(ownerRoleName))), Set.empty, dummyUserInfo.userId))
    }

    exception1.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)

    val exception2 = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(resourceType, resourceName, Map(AccessPolicyName("foo") -> AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set.empty)), Set.empty, dummyUserInfo.userId))
    }

    exception2.errorReport.statusCode shouldEqual Option(StatusCodes.BadRequest)
  }

  private def assertResourceExists(
      resource: FullyQualifiedResourceId, resourceType: ResourceType, policyDao: AccessPolicyDAO) = {
    val resultingPolicies = policyDao.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email = WorkbenchEmail("policy-randomuuid@example.com")))
    resultingPolicies should contain theSameElementsAs constructExpectedPolicies(resourceType, resource)
  }

  "Creating a resource that has at least 1 constrainable action pattern" should "succeed when no auth domain is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    val resource = runAndWait(service.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), dummyUserInfo))
    assertResourceExists(resource.fullyQualifiedId, constrainableResourceType, policyDAO)
  }

  it should "succeed when at least 1 valid auth domain group is provided" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName = "fooGroup"
    val secondMGroupName = "barGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName), dummyUserInfo))
    runAndWait(managedGroupService.createManagedGroup(ResourceId(secondMGroupName), dummyUserInfo))

    val authDomain = NonEmptyList.of(WorkbenchGroupName(managedGroupName), WorkbenchGroupName(secondMGroupName))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    val resource = runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain.toList.toSet, dummyUserInfo.userId))
    val storedAuthDomain = constrainableService.loadResourceAuthDomain(resource.fullyQualifiedId).unsafeRunSync()

    storedAuthDomain should contain theSameElementsAs authDomain.toList
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
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, dummyUserInfo.userId))
    }
  }

  it should "fail when user does not have access to at least 1 of the auth domain groups" in {
    constrainableResourceType.isAuthDomainConstrainable shouldEqual true
    constrainableService.createResourceType(constrainableResourceType).unsafeRunSync()

    val bender = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("Bender"), WorkbenchEmail("bender@planex.com"), 0)
    dirDAO.createUser(WorkbenchUser(bender.userId, None, bender.userEmail)).unsafeRunSync()

    constrainableService.createResourceType(managedGroupResourceType).unsafeRunSync()
    val managedGroupName1 = "firstGroup"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName1), dummyUserInfo))
    val managedGroupName2 = "benderIsGreat"
    runAndWait(managedGroupService.createManagedGroup(ResourceId(managedGroupName2), bender))

    val authDomain = Set(WorkbenchGroupName(managedGroupName1), WorkbenchGroupName(managedGroupName2))
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)
    intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(constrainableService.createResource(constrainableResourceType, ResourceId(UUID.randomUUID().toString), Map(viewPolicyName -> constrainablePolicyMembership), authDomain, dummyUserInfo.userId))
    }
  }

  "Loading an auth domain" should "fail when the resource does not exist" in {
    val e = intercept[WorkbenchExceptionWithErrorReport] {
      constrainableService.loadResourceAuthDomain(FullyQualifiedResourceId(constrainableResourceType.name, ResourceId(UUID.randomUUID().toString))).unsafeRunSync()
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
      runAndWait(service.createResource(defaultResourceType, ResourceId(UUID.randomUUID().toString), Map(policyName -> policyMembership), authDomain, dummyUserInfo.userId))
    }
  }

  "listUserResourceRoles" should "list the user's role when they have at least one role" in {
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resourceName, dummyUserInfo))

    val roles = runAndWait(service.listUserResourceRoles(resource, dummyUserInfo))

    roles shouldEqual Set(ResourceRoleName("owner"))
  }

  it should "return an empty set when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("a1", "", false)), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceId("resource")

    val roles = runAndWait(service.listUserResourceRoles(FullyQualifiedResourceId(resourceType.name, resourceName), dummyUserInfo))

    roles shouldEqual Set.empty

    dirDAO.deleteUser(dummyUserInfo.userId).unsafeRunSync()
  }

  "policyDao.listAccessPolicies" should "list policies for a newly created resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    constructExpectedPolicies(defaultResourceType, resource) should contain theSameElementsAs(policies)
  }

  "listResourcePolicies" should "list policies for a newly created resource without member email addresses if the User does not exist" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName)), forcedEmail)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = service.listResourcePolicies(resource).unsafeRunSync().map(_.copy(email = forcedEmail))
    policies should contain theSameElementsAs Set(expectedPolicy)
  }

  it should "list policies for a newly created resource with the member email addresses if the User has been added" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val ownerRole = defaultResourceType.roles.find(_.roleName == defaultResourceType.ownerRoleName).get
    val forcedEmail = WorkbenchEmail("policy-randomuuid@example.com")
    val expectedPolicy = AccessPolicyResponseEntry(AccessPolicyName(ownerRole.roleName.value), AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set.empty, Set(ownerRole.roleName)), forcedEmail)

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val policies = service.listResourcePolicies(resource).unsafeRunSync().map(_.copy(email = forcedEmail))
    policies should contain theSameElementsAs Set(expectedPolicy)
  }

  "overwritePolicy" should "succeed with a valid request" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), public = false)

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty)))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  "overwritePolicyMembers" should "succeed with a valid request" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(
      FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), public = false)

    runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("non_owner_action")), Set.empty)))

    runAndWait(service.overwritePolicyMembers(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, Set.empty))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync().map(_.copy(email=WorkbenchEmail("policy-randomuuid@example.com")))

    assert(policies.contains(newPolicy))
  }

  it should "succeed with a regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner")
    )
    val resource = FullyQualifiedResourceId(rt.name, ResourceId("my-resource"))

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
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("INVALID_ACTION")), public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("INVALID_ACTION")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid regex action" in {
    val rt = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceActionPattern("foo-.+-bar", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("foo-biz-bar")))), ResourceRoleName("owner")
    )
    val resource = FullyQualifiedResourceId(rt.name, ResourceId("my-resource"))

    service.createResourceType(rt).unsafeRunSync()
    runAndWait(service.createResource(rt, resource.resourceId, dummyUserInfo))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(rt, AccessPolicyName("foo"), resource, AccessPolicyMembership(Set.empty, Set(ResourceAction("foo--bar")), Set.empty)))
    }

    assert(exception.getMessage.contains("invalid action"))
  }

  it should "fail when given an invalid role" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set(ResourceRoleName("INVALID_ROLE")), Set.empty, public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set.empty, Set.empty, Set(ResourceRoleName("INVALID_ROLE")))))
    }

    assert(exception.getMessage.contains("invalid role"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  it should "fail when given an invalid member email" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val group = BasicWorkbenchGroup(WorkbenchGroupName("foo"), Set.empty, toEmail(resource.resourceTypeName.value, resource.resourceId.value, "foo"))
    val newPolicy = AccessPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("foo")), group.members, group.email, Set.empty, Set(ResourceAction("non_owner_action")), public = false)

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.overwritePolicy(defaultResourceType, newPolicy.id.accessPolicyName, newPolicy.id.resource, AccessPolicyMembership(Set(WorkbenchEmail("null@null.com")), Set.empty, Set.empty)))
    }

    assert(exception.getMessage.contains("invalid member email"))

    val policies = policyDAO.listAccessPolicies(resource).unsafeRunSync()

    assert(!policies.contains(newPolicy))
  }

  "deleteResource" should "delete the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    assert(policyDAO.listAccessPolicies(resource).unsafeRunSync().nonEmpty)

    runAndWait(service.deleteResource(resource))

    assert(policyDAO.listAccessPolicies(resource).unsafeRunSync().isEmpty)
  }

  it should "not allow a new resource to be created with the same name as the deleted resource if 'reuseIds' is false for the Resource Type" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))

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
    val localService = new ResourceService(Map(reusableResourceType.name -> reusableResourceType), null, policyDAO, dirDAO, NoExtensions, "example.com")

    localService.createResourceType(reusableResourceType).unsafeRunSync()

    val resource = FullyQualifiedResourceId(reusableResourceType.name, ResourceId("my-resource"))

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() should not be empty

    runAndWait(localService.deleteResource(resource))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() shouldBe empty

    runAndWait(localService.createResource(reusableResourceType, resource.resourceId, dummyUserInfo))
    policyDAO.listAccessPolicies(resource).unsafeRunSync() should not be empty
  }

  "add/remove SubjectToPolicy" should "add/remove subject and tolerate prior (non)existence" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource"))
    val policyName = AccessPolicyName(defaultResourceType.ownerRoleName.value)
    val otherUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("otheruserid"), WorkbenchEmail("otheruser@company.com"), 0)

    dirDAO.createUser(WorkbenchUser(otherUserInfo.userId, None, otherUserInfo.userEmail)).unsafeRunSync()

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    // assert baseline
    assertResult(Set.empty) {
      service.policyEvaluatorService.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    runAndWait(service.addSubjectToPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId))
    assertResult(Set(UserPolicyResponse(resource.resourceId, policyName, Set.empty, Set.empty, false))) {
      service.policyEvaluatorService.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    // add a second time to make sure no exception is thrown
    runAndWait(service.addSubjectToPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId))


    runAndWait(service.removeSubjectFromPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId))
    assertResult(Set.empty) {
      service.policyEvaluatorService.listUserAccessPolicies(defaultResourceType.name, otherUserInfo.userId).unsafeRunSync()
    }

    // remove a second time to make sure no exception is thrown
    runAndWait(service.removeSubjectFromPolicy(FullyQualifiedPolicyId(resource, policyName), otherUserInfo.userId))
  }

  "initResourceTypes" should "do the happy path" in {
    val adminResType = ResourceType(SamResourceTypes.resourceTypeAdminName,
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val service = new ResourceService(Map(adminResType.name -> adminResType, defaultResourceType.name -> defaultResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    val init = service.initResourceTypes().unsafeRunSync()
    init should contain theSameElementsAs(Set(adminResType, defaultResourceType))

    // assert a resource was not created for SamResourceTypes.resourceTypeAdmin
    policyDAO.listAccessPolicies(
      FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(SamResourceTypes.resourceTypeAdminName.value))).unsafeRunSync() should equal(Stream.empty)

    // assert a resource was created for defaultResourceType
    val resourceAndPolicyName = FullyQualifiedPolicyId(
      FullyQualifiedResourceId(adminResType.name, ResourceId(defaultResourceType.name.value)), AccessPolicyName("owner"))
    val policy = policyDAO.loadPolicy(resourceAndPolicyName).unsafeRunSync()
    policy.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set.empty, WorkbenchEmail(""), Set(ResourceRoleName("owner")), Set.empty, public = false)))

    // add a user to the policy and verify
    policyDAO.overwritePolicy(policy.get.copy(members = Set(dummyUserInfo.userId))).unsafeRunSync()
    val policy2 = policyDAO.loadPolicy(resourceAndPolicyName).unsafeRunSync()
    policy2.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set(dummyUserInfo.userId), WorkbenchEmail(""), Set(ResourceRoleName("owner")), Set.empty, public = false)))

    // call it again to ensure it does not fail
    service.initResourceTypes().unsafeRunSync()

    // verify the policy has not changed
    val policy3 = policyDAO.loadPolicy(resourceAndPolicyName).unsafeRunSync()
    policy3.map(_.copy(email = WorkbenchEmail(""))) should equal(Some(AccessPolicy(resourceAndPolicyName, Set(dummyUserInfo.userId), WorkbenchEmail(""), Set(ResourceRoleName("owner")), Set.empty, public = false)))
  }

  it should "fail if resourceTypeAdmin not defined" in {
    val service = new ResourceService(Map(defaultResourceType.name -> defaultResourceType), policyEvaluatorService, policyDAO, dirDAO, NoExtensions, emailDomain)

    intercept[WorkbenchException] {
      service.initResourceTypes().unsafeRunSync()
    }
  }

  "listAllFlattenedResourceUsers" should "return a flattened list of all of the users in any of a resource's policies" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId(UUID.randomUUID().toString))

    service.createResourceType(defaultResourceType).unsafeRunSync()
    runAndWait(service.createResource(defaultResourceType, resource.resourceId, dummyUserInfo))

    val dummyUserIdInfo = dirDAO.loadUser(dummyUserInfo.userId).unsafeRunSync().map { user => UserIdInfo(user.id, user.email, user.googleSubjectId) }.get
    val user1 = UserIdInfo(WorkbenchUserId("user1"), WorkbenchEmail("user1@fake.com"), None)
    val user2 = UserIdInfo(WorkbenchUserId("user2"), WorkbenchEmail("user2@fake.com"), None)
    val user3 = UserIdInfo(WorkbenchUserId("user3"), WorkbenchEmail("user3@fake.com"), None)
    val user4 = UserIdInfo(WorkbenchUserId("user4"), WorkbenchEmail("user4@fake.com"), None)
    val user5 = UserIdInfo(WorkbenchUserId("user5"), WorkbenchEmail("user5@fake.com"), None)
    val user6 = UserIdInfo(WorkbenchUserId("user6"), WorkbenchEmail("user6@fake.com"), None)

    dirDAO.createUser(WorkbenchUser(user1.userSubjectId, None, user1.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user2.userSubjectId, None, user2.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user3.userSubjectId, None, user3.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user4.userSubjectId, None, user4.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user5.userSubjectId, None, user5.userEmail)).unsafeRunSync()
    dirDAO.createUser(WorkbenchUser(user6.userSubjectId, None, user6.userEmail)).unsafeRunSync()

    val ownerPolicy = FullyQualifiedPolicyId(resource, AccessPolicyName("owner"))
    runAndWait(service.addSubjectToPolicy(ownerPolicy, user1.userSubjectId))
    runAndWait(service.addSubjectToPolicy(ownerPolicy, user2.userSubjectId))

    val group1 = BasicWorkbenchGroup(WorkbenchGroupName("group1"), Set(user3.userSubjectId, user4.userSubjectId), WorkbenchEmail("group1@fake.com"))
    val subGroup = BasicWorkbenchGroup(WorkbenchGroupName("subGroup"), Set(user6.userSubjectId), WorkbenchEmail("subgroup@fake.com"))
    val group2 = BasicWorkbenchGroup(WorkbenchGroupName("group2"), Set(user5.userSubjectId, subGroup.id), WorkbenchEmail("group2@fake.com"))

    dirDAO.createGroup(group1).unsafeRunSync()
    dirDAO.createGroup(subGroup).unsafeRunSync()
    dirDAO.createGroup(group2).unsafeRunSync()
    runAndWait(service.createPolicy(FullyQualifiedPolicyId(resource, AccessPolicyName("reader")), Set(group1.id, group2.id), Set.empty, Set.empty))

    service.listAllFlattenedResourceUsers(resource).unsafeRunSync() should contain theSameElementsAs Set(dummyUserIdInfo, user1, user2, user3, user4, user5, user6)
  }

  "loadAccessPolicyWithEmails" should "get emails for users, groups and policies" in {
    val testResult = for {
      _ <- service.createResourceType(defaultResourceType)

      testGroup <- dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName("mygroup"), Set.empty, WorkbenchEmail("group@a.com")))

      res1 <- IO.fromFuture(IO(service.createResource(defaultResourceType, ResourceId("resource1"), dummyUserInfo)))
      testPolicy <- service.listResourcePolicies(res1.fullyQualifiedId).map(_.head)

      res2 <- IO.fromFuture(IO(service.createResource(defaultResourceType, ResourceId("resource2"), dummyUserInfo)))

      newPolicy <- policyDAO.createPolicy(AccessPolicy(
        FullyQualifiedPolicyId(res2.fullyQualifiedId, AccessPolicyName("foo")), Set(testGroup.id, dummyUserInfo.userId, FullyQualifiedPolicyId(res1.fullyQualifiedId, testPolicy.policyName)), WorkbenchEmail("a@b.c"), Set.empty, Set.empty, public = false))

      membership <- service.loadAccessPolicyWithEmails(newPolicy)
    } yield {
      membership.memberEmails should contain theSameElementsAs Set(
        testGroup.email,
        dummyUserInfo.userEmail,
        testPolicy.email
      )
    }

    implicit val patienceConfig = PatienceConfig(5.seconds)
    testResult.unsafeRunSync()
  }
}
