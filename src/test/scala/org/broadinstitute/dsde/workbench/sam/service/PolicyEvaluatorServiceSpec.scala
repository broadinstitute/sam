package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{genPolicy, genResourceTypeNameExcludeManagedGroup, genUserInfo}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{blockingEc, _}
import org.broadinstitute.dsde.workbench.sam.directory.LdapDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.LdapAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest._

import scala.concurrent.Future

class PolicyEvaluatorServiceSpec extends AsyncFlatSpec with Matchers with TestSupport {
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(
    new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password),
    directoryConfig.connectionPoolSize)
  val dirDAO = new LdapDirectoryDAO(connectionPool, directoryConfig)
  val policyDAO = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val dummyUserInfo =
    UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  private val defaultResourceTypeActions = Set(
    ResourceAction("alter_policies"),
    ResourceAction("delete"),
    ResourceAction("read_policies"),
    ResourceAction("view"),
    ResourceAction("non_owner_action"))
  private val defaultResourceTypeActionPatterns = Set(
    SamResourceActionPatterns.alterPolicies,
    SamResourceActionPatterns.delete,
    SamResourceActionPatterns.readPolicies,
    ResourceActionPattern("view", "", false),
    ResourceActionPattern("non_owner_action", "", false)
  )
  private val defaultResourceType = ResourceType(
    ResourceTypeName(UUID.randomUUID().toString),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )
  private val otherResourceType = ResourceType(
    ResourceTypeName(UUID.randomUUID().toString),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )

  private val constrainableActionPatterns = Set(
    ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true))
  private val constrainableViewAction = ResourceAction("constrainable_view")
  private val constrainableResourceTypeActions = Set(constrainableViewAction)
  private val constrainableReaderRoleName = ResourceRoleName("constrainable_reader")
  private val constrainableResourceType = ResourceType(
    genResourceTypeNameExcludeManagedGroup.sample.get,
    constrainableActionPatterns,
    Set(ResourceRole(constrainableReaderRoleName, constrainableResourceTypeActions)),
    constrainableReaderRoleName
  )
  private val constrainablePolicyMembership =
    AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(constrainableViewAction), Set(constrainableReaderRoleName))

  private val managedGroupResourceType = configResourceTypes.getOrElse(
    ResourceTypeName("managed-group"),
    throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val emailDomain = "example.com"
  private val policyEvaluatorService = PolicyEvaluatorService(
    Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType),
    policyDAO)
  private val service = new ResourceService(
    Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType),
    policyEvaluatorService,
    policyDAO,
    dirDAO,
    NoExtensions,
    emailDomain
  )

  private val constrainableResourceTypes = Map(
    constrainableResourceType.name -> constrainableResourceType,
    managedGroupResourceType.name -> managedGroupResourceType)
  private val constrainablePolicyEvaluatorService = PolicyEvaluatorService(constrainableResourceTypes, policyDAO)
  private val constrainableService = new ResourceService(
    constrainableResourceTypes,
    constrainablePolicyEvaluatorService,
    policyDAO,
    dirDAO,
    NoExtensions,
    emailDomain)

  val managedGroupService = new ManagedGroupService(
    constrainableService,
    constrainablePolicyEvaluatorService,
    constrainableResourceTypes,
    policyDAO,
    dirDAO,
    NoExtensions,
    emailDomain)

  private object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)
  }

  def setup(): Future[Unit] = {
    for{
      _ <- schemaDao.init()
        _ <- schemaDao.clearDatabase()
        _ <- schemaDao.createOrgUnits()
        _ <- dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail))
    } yield ()
  }

  "listUserAccessPolicies" should "list user's access policies but not others" in {
    val resource1 = Resource(defaultResourceType.name, ResourceId("my-resource1"))
    val resource2 = Resource(defaultResourceType.name, ResourceId("my-resource2"))
    val resource3 = Resource(otherResourceType.name, ResourceId("my-resource1"))
    val resource4 = Resource(otherResourceType.name, ResourceId("my-resource2"))
    for {
      _ <- setup()
      _ <- service.createResourceType(defaultResourceType).unsafeToFuture()
      _ <- service.createResourceType(otherResourceType).unsafeToFuture()
      _ <- service.createResource(defaultResourceType, resource1.resourceId, dummyUserInfo)
      _ <- service.createResource(defaultResourceType, resource2.resourceId, dummyUserInfo)
      _ <- service.createResource(otherResourceType, resource3.resourceId, dummyUserInfo)
      _ <- service.createResource(otherResourceType, resource4.resourceId, dummyUserInfo)

      _ <- service.overwritePolicy(
        defaultResourceType,
        AccessPolicyName("in-it"),
        resource1,
        AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)
      )
      _ <- service.overwritePolicy(
        defaultResourceType,
        AccessPolicyName("not-in-it"),
        resource1,
        AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty))
      _ <- service.overwritePolicy(
        otherResourceType,
        AccessPolicyName("in-it"),
        resource3,
        AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty)
      )
      _ <- service.overwritePolicy(
        otherResourceType,
        AccessPolicyName("not-in-it"),
        resource3,
        AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty))
      res <- policyEvaluatorService.listUserAccessPolicies(defaultResourceType.name, dummyUserInfo.userId).unsafeToFuture()
    } yield {
      assertResult(
        Set(
          UserPolicyResponse(
            resource1.resourceId,
            AccessPolicyName(defaultResourceType.ownerRoleName.value),
            Set.empty,
            Set.empty),
          UserPolicyResponse(
            resource2.resourceId,
            AccessPolicyName(defaultResourceType.ownerRoleName.value),
            Set.empty,
            Set.empty),
          UserPolicyResponse(resource1.resourceId, AccessPolicyName("in-it"), Set.empty, Set.empty)
        ))(res)
    }
  }

  it should "return no auth domains where there is a resource in a constrainable type but does not have any auth domains" in {
    val policyWithConstrainable =
      SamLenses.resourceTypeNameInAccessPolicy.set(constrainableResourceType.name)(genPolicy.sample.get)
    val policy =
      (SamLenses.resourceInAccessPolicy composeLens Resource.authDomain).set(Set.empty)(policyWithConstrainable)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    for {
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService
        .createResourceType(managedGroupResourceType)
        .unsafeToFuture() // make sure managed groups in auth domain set are created. dummyUserInfo will be member of the created resourceId
      _ <- Future.traverse(resource.authDomain)(a =>
        managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(
        constrainableResourceType,
        resource.resourceId,
        Map(viewPolicyName -> constrainablePolicyMembership),
        resource.authDomain,
        dummyUserInfo.userId)
      r <- constrainablePolicyEvaluatorService
        .listUserAccessPolicies(constrainableResourceType.name, dummyUserInfo.userId)
        .unsafeToFuture()
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, viewPolicyName, Set.empty, Set.empty))
      r shouldBe expected
    }
  }

  it should "list required authDomains if constrainable" in {
    val policy = SamLenses.resourceTypeNameInAccessPolicy.set(constrainableResourceType.name)(genPolicy.sample.get)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    for {
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService
        .createResourceType(managedGroupResourceType)
        .unsafeToFuture() // make sure managed groups in auth domain set are created. dummyUserInfo will be member of the created resourceId
      _ <- Future.traverse(resource.authDomain)(a =>
        managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(
        constrainableResourceType,
        resource.resourceId,
        Map(viewPolicyName -> constrainablePolicyMembership),
        resource.authDomain,
        dummyUserInfo.userId)
      r <- constrainablePolicyEvaluatorService
        .listUserAccessPolicies(constrainableResourceType.name, dummyUserInfo.userId)
        .unsafeToFuture()
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, viewPolicyName, resource.authDomain, Set.empty))
      r shouldBe expected
    }
  }

  it should "list required authDomains and authDomains user is not a member of if constrainable" in {
    val user = genUserInfo.sample.get
    val policy = SamLenses.resourceTypeNameInAccessPolicy.set(constrainableResourceType.name)(genPolicy.sample.get)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    for {
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService.createResourceType(managedGroupResourceType).unsafeToFuture()
      _ <- Future.traverse(resource.authDomain)(a =>
        managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(
        constrainableResourceType,
        resource.resourceId,
        Map(viewPolicyName -> constrainablePolicyMembership),
        resource.authDomain,
        dummyUserInfo.userId)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(GoogleSubjectId(user.userId.value)), user.userEmail))
      _ <- constrainableService.createPolicy(policy.id, policy.members + user.userId, policy.roles, policy.actions)
      r <- constrainablePolicyEvaluatorService
        .listUserAccessPolicies(constrainableResourceType.name, user.userId)
        .unsafeToFuture()
    } yield {
      val expected = Set(
        UserPolicyResponse(resource.resourceId, policy.id.accessPolicyName, resource.authDomain, resource.authDomain))
      r shouldBe expected
    }
  }

  it should "list required authDomains and missing authDomains correctly if user is a member of a admin-notifier policy" in {
    val user = genUserInfo.sample.get
    val policy = SamLenses.resourceTypeNameInAccessPolicy.set(constrainableResourceType.name)(genPolicy.sample.get)
    val resource = policy.id.resource
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val policy2WithResourceType =
      SamLenses.resourceTypeNameInAccessPolicy.set(managedGroupResourceType.name)(genPolicy.sample.get)
    val policy2 =
      SamLenses.accessPolicyNameInAccessPolicy.set(ManagedGroupService.adminNotifierPolicyName)(policy2WithResourceType)
    val resource2 = policy2.id.resource

    for {
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType).unsafeToFuture()
      _ <- constrainableService.createResourceType(managedGroupResourceType).unsafeToFuture()
      _ <- Future.traverse(resource.authDomain)(a =>
        managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(GoogleSubjectId(user.userId.value)), user.userEmail))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(
        constrainableResourceType,
        resource.resourceId,
        Map(viewPolicyName -> constrainablePolicyMembership),
        resource.authDomain,
        dummyUserInfo.userId)
      _ <- policyDAO.createResource(resource2).unsafeToFuture()
      _ <- policyDAO
        .createPolicy(AccessPolicy(policy2.id, Set(user.userId), user.userEmail, policy2.roles, policy2.actions))
        .unsafeToFuture()
      _ <- constrainableService.createPolicy(policy.id, policy.members + user.userId, policy.roles, policy.actions)
      r <- constrainablePolicyEvaluatorService
        .listUserAccessPolicies(constrainableResourceType.name, user.userId)
        .unsafeToFuture()
    } yield {
      val expected =
        Set(
          UserPolicyResponse(resource.resourceId, policy.id.accessPolicyName, resource.authDomain, resource.authDomain))
      r shouldBe expected
    }
  }
}
