package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.{TestSupport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)

  val service = new ResourceService(policyDAO, dirDAO, "example.com")

  override protected def beforeAll(): Unit = {
    runAndWait(policyDAO.init())
  }

  private val dummyUserInfo = UserInfo("token", SamUserId("userid"), SamUserEmail("user@company.com"), 0)

  "ResourceService" should "create and delete resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2"))), ResourceRole(otherRoleName, Set(ResourceAction("a3"), ResourceAction("a2")))), ownerRoleName)
    val resourceName = ResourceName("resource")

    val policies = runAndWait(service.createResource(
      resourceType,
      resourceName,
      dummyUserInfo
    ))

    val ownerGroupName = SamGroupName(s"${resourceType.name}-${resourceName.value}-owner")
    val otherGroupName = SamGroupName(s"${resourceType.name}-${resourceName.value}-other")

    assertResult(Set(
      AccessPolicy(null, Set(ResourceAction("a1"), ResourceAction("a2")), resourceType.name, resourceName, ownerGroupName, Option(ownerRoleName)),
      AccessPolicy(null, Set(ResourceAction("a3"), ResourceAction("a2")), resourceType.name, resourceName, otherGroupName, Option(otherRoleName))
    )) {
      policies.map(_.copy(id = null))
    }

    assertResult(Some(SamGroup(ownerGroupName, Set[SamSubject](SamUserId("userid")), service.toGoogleGroupName(ownerGroupName)))) {
      runAndWait(dirDAO.loadGroup(ownerGroupName))
    }
    assertResult(Some(SamGroup(otherGroupName, Set.empty[SamSubject], service.toGoogleGroupName(otherGroupName)))) {
      runAndWait(dirDAO.loadGroup(otherGroupName))
    }

    assertResult(policies) {
      runAndWait(policyDAO.listAccessPolicies(resourceType.name, resourceName)).toSet
    }

    //cleanup
    runAndWait(service.deleteResource(resourceType, resourceName))

    assertResult(None) {
      runAndWait(dirDAO.loadGroup(ownerGroupName))
    }
    assertResult(None) {
      runAndWait(dirDAO.loadGroup(otherGroupName))
    }
    assertResult(Set.empty) {
      runAndWait(policyDAO.listAccessPolicies(resourceType.name, resourceName)).toSet
    }
  }

  it should "listUserResourceActions" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2"))), ResourceRole(otherRoleName, Set(ResourceAction("a3"), ResourceAction("a2")))), ownerRoleName)
    val resourceName1 = ResourceName("resource1")
    val resourceName2 = ResourceName("resource2")

    val userInfo = UserInfo("token", SamUserId(UUID.randomUUID().toString), SamUserEmail("user@company.com"), 0)
    runAndWait(dirDAO.createUser(SamUser(userInfo.userId, SamUserEmail("user@company.com"))))

    runAndWait(service.createResource(
      resourceType,
      resourceName1,
      userInfo
    ))
    val policies2 = runAndWait(service.createResource(
      resourceType,
      resourceName2,
      userInfo
    ))

    policies2.filter(_.role.contains(otherRoleName)).foreach { otherPolicy =>
      runAndWait(dirDAO.addGroupMember(otherPolicy.subject.asInstanceOf[SamGroupName], userInfo.userId))
    }

    assertResult(Set(ResourceAction("a1"), ResourceAction("a2"))) {
      runAndWait(service.listUserResourceActions(resourceType, resourceName1, userInfo))
    }

    assertResult(Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3"))) {
      runAndWait(service.listUserResourceActions(resourceType, resourceName2, userInfo))
    }

    assert(!runAndWait(service.hasPermission(resourceType, resourceName1, ResourceAction("a3"), userInfo)))
    assert(runAndWait(service.hasPermission(resourceType, resourceName2, ResourceAction("a3"), userInfo)))
    assert(!runAndWait(service.hasPermission(resourceType, ResourceName("doesnotexist"), ResourceAction("a3"), userInfo)))

    runAndWait(service.deleteResource(resourceType, resourceName1))
    runAndWait(service.deleteResource(resourceType, resourceName2))
    runAndWait(dirDAO.deleteUser(userInfo.userId))
  }

  it should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2")))), ownerRoleName)
    val resourceName = ResourceName("resource")

    runAndWait(service.createResource(
      resourceType,
      resourceName,
      dummyUserInfo
    ))

    val exception = intercept[WorkbenchExceptionWithErrorReport] {
      runAndWait(service.createResource(
        resourceType,
        resourceName,
        dummyUserInfo
      ))
    }

    exception.errorReport.statusCode shouldEqual Option(StatusCodes.Conflict)

    //cleanup
    runAndWait(service.deleteResource(resourceType, resourceName))
  }
}
