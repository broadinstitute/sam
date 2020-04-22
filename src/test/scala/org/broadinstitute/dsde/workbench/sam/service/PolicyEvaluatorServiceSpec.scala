package org.broadinstitute.dsde.workbench.sam.service

import java.net.URI
import java.util.UUID

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.IO
import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{genPolicy, genResourceTypeNameExcludeManagedGroup, genUserInfo, _}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, PostgresAccessPolicyDAO}
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest._
import scala.concurrent.ExecutionContext.Implicits.global

class PolicyEvaluatorServiceSpec extends FlatSpec with Matchers with TestSupport {
  val dirURI = new URI(directoryConfig.directoryUrl)
  val connectionPool = new LDAPConnectionPool(
    new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password),
    directoryConfig.connectionPoolSize)
  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)
  lazy val policyDAO: AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.blockingEc)
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
    ResourceActionPattern("constrainable_view", "Can be constrained by an auth domain", true),
    ResourceActionPattern("unconstrainable_view", "Not constrained by an auth domain", false))
  private val constrainableViewAction = ResourceAction("constrainable_view")
  private val unconstrainableViewAction = ResourceAction("unconstrainable_view")
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
    emailDomain,
    Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType),
    policyDAO,
    dirDAO)
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
  private val constrainablePolicyEvaluatorService = PolicyEvaluatorService(emailDomain, constrainableResourceTypes, policyDAO, dirDAO)
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

  def setup(): IO[Unit] = {
    for{
      _ <- clearDatabase()
      _ <- dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, Some(TestSupport.genGoogleSubjectId()), dummyUserInfo.userEmail, Some(TestSupport.genIdentityConcentratorId())))
    } yield ()
  }

  protected def clearDatabase(): IO[Unit] = IO(TestSupport.truncateAll).void

  private def savePolicyMembers(policy: AccessPolicy) = {
    policy.members.toList.parTraverse {
      case u: WorkbenchUserId => dirDAO.createUser(WorkbenchUser(u, None, WorkbenchEmail(u.value + "@foo.bar"), None)).recoverWith {
        case _: WorkbenchException => IO.pure(WorkbenchUser(u, None, WorkbenchEmail(u.value + "@foo.bar"), None))
      }
      case g: WorkbenchGroupName => managedGroupService.createManagedGroup(ResourceId(g.value), dummyUserInfo).recoverWith {
        case _: WorkbenchException => IO.pure(Resource(defaultResourceType.name, ResourceId(g.value), Set.empty))
      }
      case _ => IO.unit
    }
  }

  "hasPermission" should "return true if given action is granted through membership in another policy" in {
    val user = genUserInfo.sample.get
    val action = ResourceAction("weirdAction")

    val resource = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)

    val samplePolicy = genPolicy.sample.get
    val policyWithUser = AccessPolicy.members.set(samplePolicy.members + user.userId)(samplePolicy)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithUser)

    val resource2 = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)

    val samplePolicy2 = genPolicy.sample.get
    val policy2ExtraAction = AccessPolicy.actions.set(samplePolicy.actions + action)(samplePolicy2)
    val policy2WithNestedPolicy = AccessPolicy.members.set(Set(policy.id))(policy2ExtraAction)

    val policy2 = SamLenses.resourceIdentityAccessPolicy.set(resource2.fullyQualifiedId)(policy2WithNestedPolicy)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.traverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- resource2.authDomain.toList.traverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)
      _ <- savePolicyMembers(policy2)

      _ <- policyDAO.createResourceType(defaultResourceType)

      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createResource(resource2)

      _ <- policyDAO.createPolicy(policy)
      _ <- policyDAO.createPolicy(policy2)

      r <- service.policyEvaluatorService.hasPermission(policy2.id.resource, action, user.userId)
    } yield {
      r shouldBe true
    }

    res.unsafeRunSync()
  }

  "hasPermission" should "return false if given action is not allowed for a user" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val action = ResourceAction("weirdAction")
    val resource = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.set(samplePolicy.members + user.userId)(samplePolicy)
    val policyExcludeAction = AccessPolicy.actions.set(samplePolicy.actions - action)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyExcludeAction)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe false
    }

    res.unsafeRunSync()
  }

  it should "return false if user is not a member of the resource" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val action = genResourceAction.sample.get
    val resource = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.set(samplePolicy.members - user.userId)(samplePolicy)
    val policyExcludeAction = AccessPolicy.actions.set(samplePolicy.actions - action)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyExcludeAction)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(false)
    }

    res.unsafeRunSync()
  }


  "hasPermission" should "return false if given action is not allowed for a user using the shallow check" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val action = ResourceAction("weirdAction")
    val resource = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.set(samplePolicy.members + user.userId)(samplePolicy)
    val policyExcludeAction = AccessPolicy.actions.set(samplePolicy.actions - action)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyExcludeAction)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)

      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermissionShallowCheck(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe false
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is on a policy directly for a direct member of the policy using the shallow check" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val action = defaultResourceType.roles.head.actions.head

    val resource = genResource.sample.get.copy(authDomain = Set.empty, resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithAction = AccessPolicy.actions.modify(_ + action)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithAction)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)

      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermissionShallowCheck(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is allowed via a role for a direct member of the policy using the shallow check" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val sampleRole = defaultResourceType.roles.head.roleName
    val action = defaultResourceType.roles.head.actions.head

    val resource = genResource.sample.get.copy(authDomain = Set.empty, resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithRole = AccessPolicy.roles.modify(_ + sampleRole)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithRole)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)

      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermissionShallowCheck(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  it should "return false if given action is not present for a user via a role or directly using the shallow check" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val sampleRole = ResourceRoleName("owner")

    val action = ResourceAction("just_a_made_up_action") // an action not given to owner

    val resource = genResource.sample.get.copy(resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithRole = AccessPolicy.roles.modify(_ + sampleRole)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithRole)

    val res = for{
      _ <- setup()
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)

      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermissionShallowCheck(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(false)
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is allowed for a user and resource is not constrained by auth domains" in {
    val user = genUserInfo.sample.get
    val samplePolicy = genPolicy.sample.get
    val action = genResourceAction.sample.get
    val resource = genResource.sample.get.copy(authDomain = Set.empty, resourceTypeName = defaultResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithAction = AccessPolicy.actions.modify(_ + action)(policyWithUser)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithAction)

    val res = for{
      _ <- setup()
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- service.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  it should "list required authDomains and authDomains user is not a member of if constrainable" in {
    val user = genUserInfo.sample.get
    val resource = genResource.sample.get.copy(authDomain = Set.empty, resourceTypeName = constrainableResourceType.name)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get).copy(roles = Set.empty)

    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType)
      _ <- constrainableService.createResourceType(managedGroupResourceType)
      _ <- savePolicyMembers(policy)
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo.userId)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- constrainableService.createPolicy(policy.id, policy.members + user.userId, policy.roles, policy.actions)
      r <- constrainableService.policyEvaluatorService.listUserAccessPolicies(constrainableResourceType.name, user.userId)
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, policy.id.accessPolicyName, resource.authDomain, resource.authDomain, false))
      r shouldBe(expected)
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is allowed for a user, action is constrained by auth domains, user is a member of all required auth domains" in {
    val user = genUserInfo.sample.get
    val samplePolicy = SamLenses.resourceTypeNameInAccessPolicy.modify(_ => constrainableResourceType.name)(genPolicy.sample.get)
    val action = constrainableViewAction
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithUser)
    val policy = AccessPolicy.actions.modify(_ + action)(policyWithResource).copy(roles = Set.empty)

    val res = for{
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), user))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- constrainableService.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is allowed for a user, action is constrained by auth domains, resource has no auth domain" in {
    val user = genUserInfo.sample.get
    val samplePolicy = SamLenses.resourceTypeNameInAccessPolicy.modify(_ => constrainableResourceType.name)(genPolicy.sample.get)
    val action = constrainableViewAction
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name, authDomain = Set.empty)
    val policyWithUser = AccessPolicy.members.modify(_ + user.userId)(samplePolicy)
    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithUser)
    val policy = AccessPolicy.actions.modify(_ + action)(policyWithResource).copy(roles = Set.empty)

    val res = for{
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- constrainableService.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  it should "return false if given action is NOT allowed for a user, action is constrained by auth domains, user is a member of required auth domains" in {
    val user = genUserInfo.sample.get
    val samplePolicy = SamLenses.resourceTypeNameInAccessPolicy.modify(_ => constrainableResourceType.name)(genPolicy.sample.get)
    val action = constrainableViewAction
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name, authDomain = Set(genWorkbenchGroupName.sample.get))

    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(samplePolicy)
    val policy = AccessPolicy.actions.modify(_ + action)(policyWithResource).copy(roles = Set.empty)

    val res = for{
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), user))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(constrainableResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- constrainableService.policyEvaluatorService.hasPermission(policy.id.resource, action, user.userId)
    } yield {
      r shouldBe(false)
    }

    res.unsafeRunSync()
  }

  it should "return false if given action is allowed for a user, action is constrained by auth domains, user is NOT a member of auth domain" in {
    val user = genUserInfo.sample.get
    val probeUser = genUserInfo.sample.get
    val samplePolicy = SamLenses.resourceTypeNameInAccessPolicy.modify(_ => constrainableResourceType.name)(genPolicy.sample.get)
    val action = constrainableViewAction
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + probeUser.userId)(samplePolicy)
    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithUser)
    val policy = AccessPolicy.actions.modify(_ + action)(policyWithResource).copy(roles = Set.empty)

    val res = for{
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- dirDAO.createUser(WorkbenchUser(probeUser.userId, Some(TestSupport.genGoogleSubjectId()), probeUser.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), user))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- constrainableService.policyEvaluatorService.hasPermission(policy.id.resource, action, probeUser.userId)
    } yield {
      r shouldBe(false)
    }

    res.unsafeRunSync()
  }

  it should "return true if given action is allowed for a user, action is NOT constrained by auth domains, user is not a member of auth domain" in {
    val user = genUserInfo.sample.get
    val probeUser = genUserInfo.sample.get
    val samplePolicy = SamLenses.resourceTypeNameInAccessPolicy.modify(_ => constrainableResourceType.name)(genPolicy.sample.get)
    val action = unconstrainableViewAction
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name)
    val policyWithUser = AccessPolicy.members.modify(_ + probeUser.userId)(samplePolicy)
    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithUser)
    val policy = AccessPolicy.actions.modify(_ + action)(policyWithResource).copy(roles = Set.empty)

    val res = for{
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- dirDAO.createUser(WorkbenchUser(probeUser.userId, Some(TestSupport.genGoogleSubjectId()), probeUser.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), user))
      _ <- savePolicyMembers(policy)
      _ <- policyDAO.createResourceType(defaultResourceType)
      _ <- policyDAO.createResourceType(managedGroupResourceType)
      _ <- policyDAO.createResource(resource)
      _ <- policyDAO.createPolicy(policy)
      r <- constrainableService.policyEvaluatorService.hasPermission(policy.id.resource, action, probeUser.userId)
    } yield {
      r shouldBe(true)
    }

    res.unsafeRunSync()
  }

  "listUserAccessPolicies" should "list user's access policies but not others" in {
    val resource1 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource1"))
    val resource2 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("my-resource2"))
    val resource3 = FullyQualifiedResourceId(otherResourceType.name, ResourceId("my-resource1"))
    val resource4 = FullyQualifiedResourceId(otherResourceType.name, ResourceId("my-resource2"))

    val test = for {
      _ <- setup()
      _ <- service.createResourceType(defaultResourceType)
      _ <- service.createResourceType(otherResourceType)

      _ <- service.createResource(defaultResourceType, resource1.resourceId, dummyUserInfo)
      _ <- service.createResource(defaultResourceType, resource2.resourceId, dummyUserInfo)
      _ <- service.createResource(otherResourceType, resource3.resourceId, dummyUserInfo)
      _ <- service.createResource(otherResourceType, resource4.resourceId, dummyUserInfo)

      _ <- service.overwritePolicy(defaultResourceType, AccessPolicyName("in-it"), resource1, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty))
      _ <- service.overwritePolicy(defaultResourceType, AccessPolicyName("not-in-it"), resource1, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty))
      _ <- service.overwritePolicy(otherResourceType, AccessPolicyName("in-it"), resource3, AccessPolicyMembership(Set(dummyUserInfo.userEmail), Set(ResourceAction("alter_policies")), Set.empty))
      _ <- service.overwritePolicy(otherResourceType, AccessPolicyName("not-in-it"), resource3, AccessPolicyMembership(Set.empty, Set(ResourceAction("alter_policies")), Set.empty))
      r <- service.policyEvaluatorService.listUserAccessPolicies(defaultResourceType.name, dummyUserInfo.userId)
    } yield {
      r should contain theSameElementsAs Set(
        UserPolicyResponse(resource1.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value), Set.empty, Set.empty, false),
        UserPolicyResponse(resource2.resourceId, AccessPolicyName(defaultResourceType.ownerRoleName.value), Set.empty, Set.empty, false),
        UserPolicyResponse(resource1.resourceId, AccessPolicyName("in-it"), Set.empty, Set.empty, false))
    }

    test.unsafeRunSync()
  }

  it should "return no auth domains where there is a resource in a constrainable type but does not have any auth domains" in {
    val resource = genResource.sample.get.copy(authDomain = Set.empty)
    val policyWithConstrainable = SamLenses.resourceTypeNameInAccessPolicy.set(constrainableResourceType.name)(genPolicy.sample.get)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(policyWithConstrainable)
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType)
      _ <- constrainableService.createResourceType(managedGroupResourceType)  // make sure managed groups in auth domain set are created. dummyUserInfo will be member of the created resourceId
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo.userId)
      r <- constrainableService.policyEvaluatorService.listUserAccessPolicies(constrainableResourceType.name, dummyUserInfo.userId)
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, viewPolicyName, Set.empty, Set.empty, false))
      r shouldBe(expected)
    }

    res.unsafeRunSync()
  }

  it should "list required authDomains if constrainable" in {
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get)
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType)
      _ <- constrainableService.createResourceType(managedGroupResourceType)  // make sure managed groups in auth domain set are created. dummyUserInfo will be member of the created resourceId
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo.userId)
      r <- constrainableService.policyEvaluatorService.listUserAccessPolicies(constrainableResourceType.name, dummyUserInfo.userId)
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, viewPolicyName, resource.authDomain, Set.empty, false))
      r shouldBe(expected)
    }

    res.unsafeRunSync()
  }

  it should "list required authDomains and authDomains user is not a member of if constrainable" in {
    val user = genUserInfo.sample.get
    val resource = genResource.sample.get.copy(resourceTypeName = constrainableResourceType.name)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get).copy(roles = Set.empty)
    val viewPolicyName = AccessPolicyName(constrainableReaderRoleName.value)

    val res = for{
      _ <- setup()
      _ <- constrainableService.createResourceType(constrainableResourceType)
      _ <- constrainableService.createResourceType(managedGroupResourceType)
      _ <- resource.authDomain.toList.parTraverse(a => managedGroupService.createManagedGroup(ResourceId(a.value), dummyUserInfo))
      _ <- savePolicyMembers(policy)
      // create resource that dummyUserInfo is a member of for constrainableResourceType
      _ <- constrainableService.createResource(constrainableResourceType, resource.resourceId, Map(viewPolicyName -> constrainablePolicyMembership), resource.authDomain, dummyUserInfo.userId)
      _ <- dirDAO.createUser(WorkbenchUser(user.userId, Some(TestSupport.genGoogleSubjectId()), user.userEmail, Some(TestSupport.genIdentityConcentratorId())))
      _ <- constrainableService.createPolicy(policy.id, policy.members + user.userId, policy.roles, policy.actions)
      r <- constrainableService.policyEvaluatorService.listUserAccessPolicies(constrainableResourceType.name, user.userId)
    } yield {
      val expected = Set(UserPolicyResponse(resource.resourceId, policy.id.accessPolicyName, resource.authDomain, resource.authDomain, false))
      r shouldBe(expected)
    }

    res.unsafeRunSync()
  }
}
