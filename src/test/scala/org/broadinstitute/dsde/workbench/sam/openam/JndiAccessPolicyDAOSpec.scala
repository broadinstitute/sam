package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryConfig, JndiDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{FlatSpec, Matchers}

import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory._

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/26/17.
  */
class JndiAccessPolicyDAOSpec extends FlatSpec with Matchers with TestSupport {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dao = new JndiAccessPolicyDAO(directoryConfig)

  "JndiAccessPolicyDAO" should "create, list, delete policies" in {
    runAndWait(dao.removePolicySchema())
    runAndWait(dao.createPolicySchema())
    runAndWait(dao.createPoliciesOrgUnit())

    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)
    val policy1 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action1"), ResourceAction("action2")), typeName1, ResourceName("resource"), SamUserId("foo"))
    val policy2 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action3"), ResourceAction("action4")), typeName1, ResourceName("resource"), SamUserId("foo2"))
    val policy3 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action1"), ResourceAction("action2")), typeName2, ResourceName("resource"), SamUserId("foo"))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.resourceType, policy1.resource)).toSeq
    }

    runAndWait(dao.createPolicy(policy1))
    runAndWait(dao.createPolicy(policy2))
    runAndWait(dao.createPolicy(policy3))

    assertResult(Seq(policy1, policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.resourceType, policy1.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy1.id))

    assertResult(Seq(policy2)) {
      runAndWait(dao.listAccessPolicies(policy1.resourceType, policy1.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy2.id))

    assertResult(Seq.empty) {
      runAndWait(dao.listAccessPolicies(policy1.resourceType, policy1.resource)).toSeq
    }

    runAndWait(dao.deletePolicy(policy3.id))
  }
}
