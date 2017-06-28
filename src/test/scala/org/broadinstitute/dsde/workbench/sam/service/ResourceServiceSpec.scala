package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryConfig, JndiDirectoryDAO}
import org.scalatest.{FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with TestSupport {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)

  val service = new ResourceService(policyDAO, dirDAO)

  "ResourceService" should "create resource" in {
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole("owner", Set(ResourceAction("a1"), ResourceAction("a2"))), ResourceRole("other", Set(ResourceAction("a3"), ResourceAction("a2")))), "owner")
    val resourceName = ResourceName("resource")

    val policies = runAndWait(service.createResource(
      resourceType,
      resourceName,
      UserInfo("token", SamUserId("userid"))
    ))

    val ownerGroupName = SamGroupName(s"${resourceType.name}-${resourceName.value}-owner")
    val otherGroupName = SamGroupName(s"${resourceType.name}-${resourceName.value}-other")

    assertResult(Set(
      AccessPolicy(null, Set(ResourceAction("a1"), ResourceAction("a2")), resourceType.name, resourceName, ownerGroupName),
      AccessPolicy(null, Set(ResourceAction("a3"), ResourceAction("a2")), resourceType.name, resourceName, otherGroupName)
    )) {
      policies.map(_.copy(id = null))
    }

    assertResult(Some(SamGroup(ownerGroupName, Set[SamSubject](SamUserId("userid"))))) {
      runAndWait(dirDAO.loadGroup(ownerGroupName))
    }
    assertResult(Some(SamGroup(otherGroupName, Set.empty[SamSubject]))) {
      runAndWait(dirDAO.loadGroup(otherGroupName))
    }

    assertResult(policies) {
      runAndWait(policyDAO.listAccessPolicies(resourceType.name, resourceName)).toSet
    }

    //cleanup
    runAndWait(Future.traverse(policies) { p => policyDAO.deletePolicy(p.id) andThen { case _ => dirDAO.deleteGroup(p.subject.asInstanceOf[SamGroupName]) } })
  }
}
