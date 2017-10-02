package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAOSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dao = new JndiAccessPolicyDAO(directoryConfig)
  val dirDao = new JndiDirectoryDAO(directoryConfig)

  override protected def beforeAll(): Unit = {
    runAndWait(dirDao.init())
    runAndWait(dao.init())
  }

  "JndiAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)
    val policy1 = AccessPolicy("foo", Resource(typeName1, ResourceName("resource")), Set(WorkbenchUserId("foo")), Option(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))
    val policy2 = AccessPolicy("foo2", Resource(typeName1, ResourceName("resource")), Set(WorkbenchUserId("foo")), Option(ResourceRoleName("role1")), Set(ResourceAction("action3"), ResourceAction("action4")))
    val policy3 = AccessPolicy("foo3", Resource(typeName2, ResourceName("resource")), Set(WorkbenchUserId("foo")), Option(ResourceRoleName("role1")), Set(ResourceAction("action1"), ResourceAction("action2")))

    runAndWait(dao.createResourceType(typeName1))
    runAndWait(dao.createResourceType(typeName2))

    runAndWait(dao.createResource(policy1.resource))
    //policy2's resource already exists
    runAndWait(dao.createResource(policy3.resource))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy2.resource)).toSeq
      runAndWait(dao.listAccessPolicies(policy3.resource)).toSeq
    }

    runAndWait(dao.createPolicy(policy1))
    runAndWait(dao.createPolicy(policy2))
    runAndWait(dao.createPolicy(policy3))

    assertResult(Seq(policy1, policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.resource)).toSeq
    }

    assertResult(Seq(policy3)) {
      runAndWait(dao.listAccessPolicies(policy3.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy1))

    assertResult(Seq(policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy2))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy3))
  }
}
