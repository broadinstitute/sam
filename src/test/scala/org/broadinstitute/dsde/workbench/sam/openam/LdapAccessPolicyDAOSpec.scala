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
import org.broadinstitute.dsde.workbench.sam.openam.LdapAccessPolicyDAOSpec._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures


/**
  * Created by dvoet on 6/26/17.
  */
class LdapAccessPolicyDAOSpec extends AsyncFlatSpec with ScalaFutures with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
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
      ls1 shouldBe(Set.empty)
      ls2 shouldBe(Set.empty)
      ls3 shouldBe(Set.empty)

      lsPolicy1 should contain theSameElementsAs (Seq(policy1, policy2))
      lsPolicy3 shouldBe(Set(policy3))
      lsAfterDeletePolicy1 shouldBe Set(policy2)
      lsAfterDeletePolicy2 shouldBe Set.empty
      ls3AfterDeletePolicy3 shouldBe Set.empty
    }

    res.unsafeToFuture()
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
    res.unsafeToFuture
  }

  "LdapAccessPolicyDAO listUserPolicyResponse" should "return UserPolicyResponse" in {
    val resource = genResource.sample.get
    val policy = SamLenses.resourceIdentityAccessPolicy.set(resource.fullyQualifiedId)(genPolicy.sample.get)
    val res = for{
      _ <- setup()
      _ <- dao.createResourceType(resource.resourceTypeName)
      _ <- dao.createResource(resource)
      r <- dao.listResourceWithAuthdomains(resource.resourceTypeName, Set(resource.resourceId))
    } yield r

    res.unsafeToFuture().map(x => x shouldBe(Set(Resource(policy.id.resource.resourceTypeName, policy.id.resource.resourceId, resource.authDomain))))
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

    res.unsafeToFuture().map(x => x shouldBe(Set(ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName))))
  }
}

object LdapAccessPolicyDAOSpec{
  implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
  import scala.concurrent.ExecutionContext.Implicits.global

  private val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc)
  val dirDao = new LdapDirectoryDAO(connectionPool, directoryConfig, blockingEc)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  // before() doesn't seem to work well with AsyncFlatSpec
  def setup(): IO[Unit] = IO.fromFuture(IO(schemaDao.init())) <* IO.fromFuture(IO(schemaDao.clearDatabase())) <* IO.fromFuture(IO(schemaDao.createOrgUnits()))
}
