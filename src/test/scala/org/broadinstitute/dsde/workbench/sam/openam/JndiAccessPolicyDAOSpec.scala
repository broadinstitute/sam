package org.broadinstitute.dsde.workbench.sam.openam

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
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

  override protected def beforeAll(): Unit = {
    runAndWait(dao.init())
  }

  "JndiAccessPolicyDAO" should "create, list, delete policies" in {
    val typeName1 = ResourceTypeName(UUID.randomUUID().toString)
    val typeName2 = ResourceTypeName(UUID.randomUUID().toString)
    val policy1 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action1"), ResourceAction("action2")), typeName1, ResourceName("resource"), SamUserId("foo"), None)
    val policy2 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action3"), ResourceAction("action4")), typeName1, ResourceName("resource"), SamUserId("foo2"), None)
    val policy3 = AccessPolicy(AccessPolicyId(UUID.randomUUID().toString), Set(ResourceAction("action1"), ResourceAction("action2")), typeName2, ResourceName("resource"), SamUserId("foo"), None)

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
