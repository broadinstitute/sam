package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroup, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.{DirectoryConfig, SchemaLockConfig}
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfter with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  val dao = new JndiAccessPolicyDAO(directoryConfig)
  val dirDao = new JndiDirectoryDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
  }

  def toEmail(resourceType: String, resourceId: String, policyName: String) = {
    WorkbenchEmail(s"policy-$resourceType-$resourceId-$policyName@dev.test.firecloud.org")
  }

  "JndiAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)

    val policy1Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName1.value, "resource", "role1-a"))
    val policy2Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-b"), Set(WorkbenchUserId("foo")),toEmail(typeName1.value, "resource", "role1-b"))
    val policy3Group = BasicWorkbenchGroup(WorkbenchGroupName("role1-a"), Set(WorkbenchUserId("foo")), toEmail(typeName2.value, "resource", "role1-a"))

    val policy1 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-a")), policy1Group.members, policy1Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))
    val policy2 = AccessPolicy(ResourceAndPolicyName(Resource(typeName1, ResourceId("resource")), AccessPolicyName("role1-b")), policy2Group.members, policy2Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action3"), ResourceAction("action4")))
    val policy3 = AccessPolicy(ResourceAndPolicyName(Resource(typeName2, ResourceId("resource")), AccessPolicyName("role1-a")), policy3Group.members, policy3Group.email, Set(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))

    runAndWait(dao.createResourceType(typeName1))
    runAndWait(dao.createResourceType(typeName2))

    runAndWait(dao.createResource(policy1.id.resource))
    //policy2's resource already exists
    runAndWait(dao.createResource(policy3.id.resource))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy2.id.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy3.id.resource)).toSeq
    }

    runAndWait(dao.createPolicy(policy1))
    runAndWait(dao.createPolicy(policy2))
    runAndWait(dao.createPolicy(policy3))

    assertResult(Seq(policy1, policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    assertResult(Seq(policy3)) {
      runAndWait(dao.listAccessPolicies(policy3.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy1))

    assertResult(Seq(policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy2))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.id.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy3))
  }
}
