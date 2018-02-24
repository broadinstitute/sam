package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.JndiAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.schema.JndiSchemaDAO
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by gpolumbo on 2/21/2018
  */
class ManagedGroupServiceSpec extends FlatSpec with Matchers with TestSupport with MockitoSugar
  with BeforeAndAfter with BeforeAndAfterAll with ScalaFutures {

  val directoryConfig = ConfigFactory.load().as[DirectoryConfig]("directory")
  val dirDAO = new JndiDirectoryDAO(directoryConfig)
  val policyDAO = new JndiAccessPolicyDAO(directoryConfig)
  val schemaDao = new JndiSchemaDAO(directoryConfig)

  private val ownerRoleName = ResourceRoleName("admin")
  private val accessPolicyNames = Set(AccessPolicyName(ownerRoleName.value), AccessPolicyName(ManagedGroupService.MemberRoleName.value))
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ownerRoleName, resourceActions)
  private val defaultRoles = Set(defaultOwnerRole, ResourceRole(ManagedGroupService.MemberRoleName, Set.empty))
  private val managedGroupResourceType = ResourceType(ManagedGroupService.ManagedGroupTypeName, resourceActionPatterns, defaultRoles, ownerRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val resourceService = new ResourceService(resourceTypes, policyDAO, dirDAO, NoExtensions, "example.com")

  val managedGroupService = new ManagedGroupService(resourceService, resourceTypes)

  private val dummyUserInfo = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("userid"), WorkbenchEmail("user@company.com"), 0)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    runAndWait(schemaDao.init())
  }

  before {
    runAndWait(schemaDao.clearDatabase())
    runAndWait(schemaDao.createOrgUnits())
    runAndWait(resourceService.createResourceType(managedGroupResourceType))
  }

  "ManagedGroupService" should "create a managed group" in {
    val resourceId = ResourceId("myNewGroup")

    runAndWait(managedGroupService.createManagedGroup(resourceId, dummyUserInfo))

    val expectedResource = Resource(ManagedGroupService.ManagedGroupTypeName, resourceId)
    val policies = runAndWait(policyDAO.listAccessPolicies(expectedResource))
    policies.isEmpty shouldEqual false
  }

}
