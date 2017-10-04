package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 6/27/17.
  */
class ResourceServiceSpec extends FlatSpec with Matchers with TestSupport with BeforeAndAfterAll {
  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  val service = new ResourceService(policyDAO, dirDAO, "example.com")

  override protected def beforeAll(): Unit = {
    runAndWait(schemaDao.init())
  }

  private val dummyUserInfo = UserInfo("token", WorkbenchUserId("userid"), WorkbenchUserEmail("user@company.com"), 0)

  def toEmail(resourceType: String, resourceName: String, policyName: String) = {
    WorkbenchGroupEmail(s"policy-$resourceType-$resourceName-$policyName@dev.test.firecloud.org")
  }

  "ResourceService" should "create and delete resource" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2"))), ResourceRole(otherRoleName, Set(ResourceAction("a3"), ResourceAction("a2")))), ownerRoleName)
    val resourceName = ResourceName("resource")

    runAndWait(service.createResourceType(resourceType))

    val policies = runAndWait(service.createResource(
      resourceType,
      resourceName,
      dummyUserInfo
    ))

    val ownerGroupName = WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-owner")
    val otherGroupName = WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-other")

    assertResult(Set(
      AccessPolicy(ownerRoleName.value, Resource(resourceType.name, resourceName), WorkbenchGroup(WorkbenchGroupName(ownerRoleName.value), Set(dummyUserInfo.userId), toEmail(resourceType.name.value, resourceName.value, ownerRoleName.value)), Set(ownerRoleName), Set(ResourceAction("a1"), ResourceAction("a2"))),
      AccessPolicy(otherRoleName.value, Resource(resourceType.name, resourceName), WorkbenchGroup(WorkbenchGroupName(otherRoleName.value), Set.empty, toEmail(resourceType.name.value, resourceName.value, otherRoleName.value)), Set(otherRoleName), Set(ResourceAction("a3"), ResourceAction("a2")))
    )) {
      policies
    }

    assertResult(policies) {
      runAndWait(policyDAO.listAccessPolicies(Resource(resourceType.name, resourceName))).toSet
    }

    //cleanup
    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName)))

    assertResult(None) {
      runAndWait(dirDAO.loadGroup(ownerGroupName))
    }
    assertResult(None) {
      runAndWait(dirDAO.loadGroup(otherGroupName))
    }
    assertResult(Set.empty) {
      runAndWait(policyDAO.listAccessPolicies(Resource(resourceType.name, resourceName))).toSet
    }
  }

  it should "listUserResourceActions" in {
    val ownerRoleName = ResourceRoleName("owner")
    val otherRoleName = ResourceRoleName("other")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2"))), ResourceRole(otherRoleName, Set(ResourceAction("a3"), ResourceAction("a2")))), ownerRoleName)
    val resourceName1 = ResourceName("resource1")
    val resourceName2 = ResourceName("resource2")

    runAndWait(service.createResourceType(resourceType))

    val userInfo = UserInfo("token", WorkbenchUserId(UUID.randomUUID().toString), WorkbenchUserEmail("user@company.com"), 0)
    runAndWait(dirDAO.createUser(WorkbenchUser(userInfo.userId, WorkbenchUserEmail("user@company.com"))))

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

    policies2.filter(_.roles.contains(otherRoleName)).foreach { otherPolicy =>
      val members = otherPolicy.members.copy(members = Set(userInfo.userId))
      runAndWait(service.accessPolicyDAO.overwritePolicy(otherPolicy.copy(members = members)))
    }

    assertResult(Set(ResourceAction("a1"), ResourceAction("a2"))) {
      runAndWait(service.listUserResourceActions(resourceType.name, resourceName1, userInfo))
    }

    assertResult(Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3"))) {
      runAndWait(service.listUserResourceActions(resourceType.name, resourceName2, userInfo))
    }

    assert(!runAndWait(service.hasPermission(resourceType.name, resourceName1, ResourceAction("a3"), userInfo)))
    assert(runAndWait(service.hasPermission(resourceType.name, resourceName2, ResourceAction("a3"), userInfo)))
    assert(!runAndWait(service.hasPermission(resourceType.name, ResourceName("doesnotexist"), ResourceAction("a3"), userInfo)))

    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName1)))
    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName2)))
    runAndWait(dirDAO.deleteUser(userInfo.userId))
  }

  it should "detect conflict on create" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1"), ResourceAction("a2"), ResourceAction("a3")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1"), ResourceAction("a2")))), ownerRoleName)
    val resourceName = ResourceName("resource")

    runAndWait(service.createResourceType(resourceType))

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
    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName)))
  }

  it should "listUserResourceRoles when they have at least one role" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceName("resource")

    runAndWait(service.createResourceType(resourceType))

    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    runAndWait(service.createResource(
      resourceType,
      resourceName,
      dummyUserInfo
    ))

    val roles = runAndWait(service.listUserResourceRoles(
      resourceType.name,
      resourceName,
      dummyUserInfo
    ))

    roles shouldEqual Set(ResourceRoleName("owner"))

    runAndWait(service.deleteResource(Resource(resourceType.name, resourceName)))
    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }

  it should "return an empty set from listUserResourceRoles when the resource doesn't exist" in {
    val ownerRoleName = ResourceRoleName("owner")
    val resourceType = ResourceType(ResourceTypeName(UUID.randomUUID().toString), Set(ResourceAction("a1")), Set(ResourceRole(ownerRoleName, Set(ResourceAction("a1")))), ownerRoleName)
    val resourceName = ResourceName("resource")


    runAndWait(service.directoryDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))

    val roles = runAndWait(service.listUserResourceRoles(
      resourceType.name,
      resourceName,
      dummyUserInfo
    ))

    roles shouldEqual Set.empty

    runAndWait(service.directoryDAO.deleteUser(dummyUserInfo.userId))
  }
}
