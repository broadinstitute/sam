package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import cats.effect.IO
import cats.implicits._
import com.unboundid.ldap.sdk.{LDAPConnection, LDAPConnectionPool}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest._

/**
  * Created by dvoet on 6/26/17.
  */
class LdapAccessPolicyDAOSpec extends AsyncFlatSpec with ScalaFutures with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  private val connectionPool = new LDAPConnectionPool(new LDAPConnection(dirURI.getHost, dirURI.getPort, directoryConfig.user, directoryConfig.password), directoryConfig.connectionPoolSize)
  val dao = new LdapAccessPolicyDAO(connectionPool, directoryConfig, blockingEc)
  val dirDao = new LdapDirectoryDAO(connectionPool, directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  // before() doesn't seem to work well with AsyncFlatSpec
  def setup(): Future[Unit] = schemaDao.init() <* schemaDao.clearDatabase() <* schemaDao.createOrgUnits()

  def toEmail(resourceType: String, resourceId: String, policyName: String) = {
    WorkbenchEmail(s"policy-$resourceType-$resourceId-$policyName@dev.test.firecloud.org")
  }

  "LdapAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName1.value, "resource", "role1-a"))
    val policy2Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-b"), Set(WorkbenchUserId("foo")),toEmail(typeName1.value, "resource", "role1-b"))
    val policy3Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName2.value, "resource", "role1-a"))

    val policy1 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-a")), policy1Group.members, policy1Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))
    val policy2 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-b")), policy2Group.members, policy2Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action3"), ResourceAction("action4")))
    val policy3 = AccessPolicy(ResourceAndPolicyName(Resource(typeName2, ResourceId("resource")), AccessPolicyName("role1-a")), policy3Group.members, policy3Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))


    val res = for{
      _ <- IO.fromFuture(IO(setup()))
      _ <- dao.createResourceType(typeName1)
      _ <- dao.createResourceType(typeName2)
      _ <- dao.createResource(policy1.id.resource)
      //policy2's resource already exists
      _ <- dao.createResource(policy3.id.resource)

      ls1 <- dao.listAccessPolicies(policy1.id.resource)
      ls2 <- dao.listAccessPolicies(policy2.id.resource)
      ls3 <- dao.listAccessPolicies(policy3.id.resource)

      _ <- dao.createPolicy(policy1)
      _ <- dao.createPolicy(policy2)
      _ <- dao.createPolicy(policy3)

      lsPolicy1 <- dao.listAccessPolicies(policy1.id.resource)
      lsPolicy3 <- dao.listAccessPolicies(policy3.id.resource)

      _ <- dao.deletePolicy(policy1)
      lsAfterDeletePolicy1 <- dao.listAccessPolicies(policy1.id.resource)

      _ <- dao.deletePolicy(policy2)
      lsAfterDeletePolicy2 <- dao.listAccessPolicies(policy1.id.resource)

      _ <- dao.deletePolicy(policy3)
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
      resources <- IO.fromFuture(IO(dao.listResourcesConstrainedByGroup(authDomain)))
    } yield {
      resources should contain theSameElementsAs Set(resource1, resource2)
    }
    res.unsafeToFuture
  }

  "LdapAccessPolicyDAO listUserPolicyResponse" should "return UserPolicyResponse" in {
    val policy = genPolicy.sample.get
    val res = for{
      _ <- IO.fromFuture(IO(setup()))
      _ <- dao.createResourceType(policy.id.resource.resourceTypeName)
      _ <- dao.createResource(policy.id.resource)
      r <- dao.listResourceWithAuthdomains(policy.id.resource.resourceTypeName, Set(policy.id.resource.resourceId))
    } yield r

    res.unsafeToFuture().map(x => x shouldBe(Set(Resource(policy.id.resource.resourceTypeName, policy.id.resource.resourceId, policy.id.resource.authDomain))))
  }
}
