package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.mockito.Mockito.{verify, when}
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._
import scala.util.Success

class ReproSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures with OptionValues with Eventually {

  implicit val patience = PatienceConfig(1 minute)

  private val config = ConfigFactory.load()
  val directoryConfig = config.as[DirectoryConfig]("directory")
  val schemaLockConfig = ConfigFactory.load().as[SchemaLockConfig]("schemaLock")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig, schemaLockConfig)

  private val resourceId = ResourceId("myNewGroup")
  private val expectedResource = Resource(ManagedGroupService.managedGroupTypeName, resourceId)
  private val adminPolicy = ResourceAndPolicyName(expectedResource, ManagedGroupService.adminPolicyName)
  private val memberPolicy = ResourceAndPolicyName(expectedResource, ManagedGroupService.memberPolicyName)

  //Note: we intentionally use the Managed Group resource type loaded from reference.conf for the tests here.
  private val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
  private val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap
  private val managedGroupResourceType = resourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))
  private val testDomain = "example.com"

  private val resourceService = new ResourceService(resourceTypeMap, policyDAO, dirDAO, NoExtensions, testDomain)
  private val managedGroupService = new ManagedGroupService(resourceService, resourceTypeMap, policyDAO, dirDAO, NoExtensions, testDomain)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  def makeResourceType(resourceType: ResourceType): ResourceTypeName = runAndWait(resourceService.createResourceType(resourceType))

  def assertPoliciesOnResource(resource: Resource, policyDAO: JndiAccessPolicyDAO = policyDAO, expectedPolicies: Map[AccessPolicyName, Set[WorkbenchSubject]]) = {
    val policies = runAndWait(policyDAO.listAccessPolicies(resource))
    policies.map(_.id.accessPolicyName.value) should contain theSameElementsAs(expectedPolicies.map(_._1.value))
    expectedPolicies.foreach { case(policyName, members) =>
      runAndWait(policyDAO.loadPolicy(ResourceAndPolicyName(resource, policyName))).map(_.members) shouldBe Some(members)
    }
  }

  def assertMakeGroup(groupId: String = resourceId.value, managedGroupService: ManagedGroupService = managedGroupService, policyDAO: JndiAccessPolicyDAO = policyDAO): Resource = {
    val resource: Resource = makeGroup(groupId, managedGroupService)
    val intendedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
    resource shouldEqual intendedResource
    assertPoliciesOnResource(resource, expectedPolicies = Map(ManagedGroupService.adminPolicyName -> Set(dummyUserInfo.userId), ManagedGroupService.memberPolicyName -> Set.empty, ManagedGroupService.adminNotifierPolicyName -> Set.empty))
    runAndWait(dirDAO.loadGroup(WorkbenchGroupName(groupId))).map(_.members).get should contain theSameElementsAs(Seq(ResourceAndPolicyName(resource, ManagedGroupService.memberPolicyName), ResourceAndPolicyName(resource, ManagedGroupService.adminPolicyName)))
    resource
  }

  private def makeGroup(groupName: String, managedGroupService: ManagedGroupService, userInfo: UserInfo = dummyUserInfo) = {
    makeResourceType(managedGroupResourceType)
    runAndWait(managedGroupService.createManagedGroup(ResourceId(groupName), userInfo))
  }

  def assertIsMemberOf(groupName: String, userId: WorkbenchUserId) = {
    val results = runAndWait(dirDAO.listUsersGroups(userId))
    results should contain(ResourceAndPolicyName(Resource(managedGroupResourceType.name, ResourceId(groupName)), ManagedGroupService.adminPolicyName))
    assert(results.contains(WorkbenchGroupName(groupName)))
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
    runAndWait(dirDAO.createUser(WorkbenchUser(dummyUserInfo.userId, dummyUserInfo.userEmail)))
  }

  "ReproSpec" should "create a bad managed group" in {
    makeGroup("start", managedGroupService)
    for(i <- 1 to 100) {
      println(s"iteration $i")
      val groupName = s"groupNumber$i"
      assertMakeGroup(groupName)
      assertIsMemberOf(groupName, dummyUserInfo.userId)
      runAndWait(managedGroupService.deleteManagedGroup(ResourceId(groupName)))
    }
  }

  it should "foo" in {
    makeResourceType(managedGroupResourceType)
    for(i <- 1 to 100) {
      println(s"iteration $i")
      val innerName = s"inner$i"
      val outerName = s"outer$i"

      val resource = policyDAO.createResource(Resource(managedGroupResourceType.name, ResourceId(s"N$i"))).futureValue
      val inner = policyDAO.createPolicy(AccessPolicy(ResourceAndPolicyName(resource, AccessPolicyName(innerName)), Set(dummyUserInfo.userId), WorkbenchEmail("inner@bar.com"), Set.empty, Set.empty)).futureValue
      val outer = dirDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(outerName), Set(inner.id), WorkbenchEmail("outer@bar.com"))).futureValue
      dirDAO.listUsersGroups(dummyUserInfo.userId).futureValue should contain(outer.id)
    }
  }

  private def makeResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Resource = runAndWait(resourceService.createResource(resourceType, resourceId, userInfo))

}
