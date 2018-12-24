package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import cats.effect.IO
import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.ehcache.config.builders.{CacheConfigurationBuilder, CacheManagerBuilder, ExpiryPolicyBuilder, ResourcePoolsBuilder}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures


/**
  * Created by dvoet on 6/26/17.
  */
class LdapAccessPolicyDAOSpec extends FlatSpec with ScalaFutures with TestSupport with Matchers with BeforeAndAfter with BeforeAndAfterAll {
  import scala.concurrent.ExecutionContext.Implicits.global

  private val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapAccessPolicyDAO(semaphore, connectionPool, directoryConfig, blockingEc, TestSupport.testMemberOfCache, TestSupport.testResourceCache)
  val dirDao = new LdapDirectoryDAO(semaphore, connectionPool, directoryConfig, blockingEc, TestSupport.testMemberOfCache)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  // before() doesn't seem to work well with AsyncFlatSpec
  def setup(): IO[Unit] = IO.fromFuture(IO(schemaDao.init())) <* IO.fromFuture(IO(schemaDao.clearDatabase())) <* IO.fromFuture(IO(schemaDao.createOrgUnits()))

  def toEmail(resourceType: String, resourceId: String, policyName: String) = {
    WorkbenchEmail(s"policy-$resourceType-$resourceId-$policyName@dev.test.firecloud.org")
  }

  "LdapAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName1.value, "resource", "role1-a"))
    val policy2Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-b"), Set(WorkbenchUserId("foo")),toEmail(typeName1.value, "resource", "role1-b"))
    val policy3Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName2.value, "resource", "role1-a"))

    val resource1 = Resource(typeName1, ResourceId("resource"), Set.empty)
    val resource2 = Resource(typeName1, ResourceId("resource"), Set.empty)
    val resource3 = Resource(typeName2, ResourceId("resource"), Set.empty)

    val policy1 = AccessPolicy(
      FullyQualifiedPolicyId(resource1.fullyQualifiedId, AccessPolicyName("role1-a")), policy1Group.members, policy1Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")), public = false)
    val policy2 = AccessPolicy(
      FullyQualifiedPolicyId(resource2.fullyQualifiedId, AccessPolicyName("role1-b")), policy2Group.members, policy2Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action3"), ResourceAction("action4")), public = false)
    val policy3 = AccessPolicy(
      FullyQualifiedPolicyId(resource3.fullyQualifiedId, AccessPolicyName("role1-a")), policy3Group.members, policy3Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")), public = false)


    val res = for{
      _ <- setup()
      _ <- dao.createResourceType(typeName1)
      _ <- dao.createResourceType(typeName2)
      _ <- dao.createResource(resource1)
      //policy2's resource already exists
      _ <- dao.createResource(resource3)

      ls1 <- dao.listAccessPolicies(policy1.id.resource)
      ls2 <- dao.listAccessPolicies(policy2.id.resource)
      ls3 <- dao.listAccessPolicies(policy3.id.resource)

      _ <- dao.createPolicy(policy1)
      _ <- dao.createPolicy(policy2)
      _ <- dao.createPolicy(policy3)

      lsPolicy1 <- dao.listAccessPolicies(policy1.id.resource)
      lsPolicy3 <- dao.listAccessPolicies(policy3.id.resource)

      _ <- dao.deletePolicy(policy1.id)
      lsAfterDeletePolicy1 <- dao.listAccessPolicies(policy1.id.resource)

      _ <- dao.deletePolicy(policy2.id)
      lsAfterDeletePolicy2 <- dao.listAccessPolicies(policy1.id.resource)

      _ <- dao.deletePolicy(policy3.id)
      ls3AfterDeletePolicy3 <- dao.listAccessPolicies(policy3.id.resource)
    } yield{
      ls1 shouldBe(Stream.empty)
      ls2 shouldBe(Stream.empty)
      ls3 shouldBe(Stream.empty)

      lsPolicy1 should contain theSameElementsAs (Seq(policy1, policy2))
      lsPolicy3 shouldBe(Stream(policy3))
      lsAfterDeletePolicy1 shouldBe Stream(policy2)
      lsAfterDeletePolicy2 shouldBe Stream.empty
      ls3AfterDeletePolicy3 shouldBe Stream.empty
    }

    res.unsafeRunSync()
  }

  it should "list the resources constrained by the given managed group" in {
    val authDomain = WorkbenchGroupName("authDomain")
    val resourceTypeName = ResourceTypeName(UUID.randomUUID().toString)
    val resource1 = Resource(resourceTypeName, ResourceId("rid1"), Set(authDomain))
    val resource2 = Resource(resourceTypeName, ResourceId("rid2"), Set(authDomain))

    val res = for {
      _ <- dao.createResourceType(resourceTypeName)
      _ <- dao.createResource(resource1)
      _ <- dao.createResource(resource2)
      resources <- dao.listResourcesConstrainedByGroup(authDomain)
    } yield {
      resources should contain theSameElementsAs Set(resource1, resource2)
    }
    res.unsafeRunSync()
  }

  "LdapAccessPolicyDAO listUserPolicyResponse" should "return UserPolicyResponse" in {
    val cache = createResourceCache("test-resource-cache-1")
    val testDao = new LdapAccessPolicyDAO(semaphore, connectionPool, directoryConfig, blockingEc, TestSupport.testMemberOfCache, cache)
    val resource = genResource.sample.get
    val cachedResource = genResource.sample.get.copy(resourceTypeName = resource.resourceTypeName)
    cache.put(cachedResource.fullyQualifiedId, cachedResource)
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get)
    val res = for{
      _ <- setup()
      _ <- testDao.createResourceType(resource.resourceTypeName)
      _ <- testDao.createResource(resource)
      // put cachedResource in ldap with different auth domains so we are sure we don't actually look it up
      _ <- testDao.createResource(Resource.authDomain.set(genAuthDomains.sample.get)(cachedResource))
      resourceIds = Set(resource.resourceId, cachedResource.resourceId)
      r <- testDao.listResourcesWithAuthdomains(resource.resourceTypeName, resourceIds)
      cached <- testDao.listResourcesWithAuthdomains(resource.resourceTypeName, resourceIds)
    } yield (r, cached)

    val (firstResponse, secondResponse) =  res.unsafeRunSync()
    firstResponse shouldBe Set(cachedResource, Resource(policy.id.resource.resourceTypeName, policy.id.resource.resourceId, resource.authDomain))
    secondResponse shouldBe firstResponse
  }

  "listAccessPolicies" should "return all ResourceIdAndPolicyName user is a member of" in{
    val user = genWorkbenchUser.sample.get
    val resource = genResource.sample.get
    val policyWithResource = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get)
    val policy = AccessPolicy.members.modify(_ + user.id)(policyWithResource)
    val res = for{
      _ <- setup()
      _ <- dirDao.createUser(user)
      _ <- dao.createResourceType(policy.id.resource.resourceTypeName)
      _ <- dao.createResource(resource)
      _ <- dao.createPolicy(policy)
      resources <- dao.listAccessPolicies(policy.id.resource.resourceTypeName, user.id)
    } yield resources

    res.unsafeRunSync() shouldBe Set(ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName))
  }

  private def createResourceCache(cacheName: String) = {
    val cacheManager = CacheManagerBuilder.newCacheManagerBuilder
      .withCache(
        cacheName,
        CacheConfigurationBuilder
          .newCacheConfigurationBuilder(classOf[FullyQualifiedResourceId], classOf[Resource], ResourcePoolsBuilder.heap(10))
          .withExpiry(ExpiryPolicyBuilder.timeToLiveExpiration(java.time.Duration.ofMinutes(10)))
      )
      .build
    cacheManager.init()
    val cache = cacheManager.getCache(cacheName, classOf[FullyQualifiedResourceId], classOf[Resource])
    cache
  }
}