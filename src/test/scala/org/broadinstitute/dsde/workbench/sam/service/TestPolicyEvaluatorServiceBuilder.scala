package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchGroup
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, StatefulMockAccessPolicyDaoBuilder, StatefulMockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, ResourceAction, ResourceActionPattern, ResourceRole, ResourceRoleName, ResourceType, ResourceTypeName, SamUser}
import org.mockito.scalatest.MockitoSugar

import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestPolicyEvaluatorServiceBuilder(policyDAOOpt: Option[AccessPolicyDAO] = None, directoryDAOOpt: Option[DirectoryDAO] = None)
                                            (implicit val executionContext: ExecutionContext, val openTelemetry: OpenTelemetryMetrics[IO]) extends MockitoSugar {
  //private var policies: Map[WorkbenchGroupIdentity, WorkbenchGroup] = Map()
  // Users that:
  // - "exist" in the DirectoryDao
  // - have neither a GoogleSubjectID nor AzureB2CId
  // - are not enabled
  private val existingUsers: mutable.Set[SamUser] = mutable.Set.empty

  // What defines an enabled user:
  // - "exist" in the DirectoryDao
  // - have a GoogleSubjectId and/or an AzureB2CId
  // - are in the "All_Users" group in Sam
  // - are in the "All_Users" group in Google
  private val enabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val disabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  private var maybeWorkbenchGroup: Option[WorkbenchGroup] = None
  private val existingPolicies: mutable.Set[AccessPolicy] = mutable.Set.empty
  private val emailDomain = "example.com"
  private val defaultResourceTypeActions =
    Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  private val defaultResourceTypeActionPatterns = Set(
    SamResourceActionPatterns.alterPolicies,
    SamResourceActionPatterns.delete,
    SamResourceActionPatterns.readPolicies,
    ResourceActionPattern("view", "", false),
    ResourceActionPattern("non_owner_action", "", false)
  )
  private val workspaceResourceType = ResourceType(
    ResourceTypeName("workspace"),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )

  // val mockPolicyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)

  def withExistingPolicy(policy: AccessPolicy): TestPolicyEvaluatorServiceBuilder = withExistingPolicies(List(policy))
  def withExistingPolicies(policies: Iterable[AccessPolicy]): TestPolicyEvaluatorServiceBuilder = {
    existingPolicies.addAll(policies)
    this
  }

  def build: PolicyEvaluatorService = {
    val directoryDAO = directoryDAOOpt match {
      case Some(dao) => dao
      case None => buildDirectoryDao()
    }
    val policyDAO = policyDAOOpt match {
      case Some(dao) => dao
      case None => buildAccessPolicyDao()
    }

    new PolicyEvaluatorService(emailDomain, Map(workspaceResourceType.name -> workspaceResourceType), policyDAO, directoryDAO)
  }

  private def buildAccessPolicyDao(): AccessPolicyDAO = {
    val mockAccessPolicyDaoBuilder = StatefulMockAccessPolicyDaoBuilder()

    existingPolicies.foreach(mockAccessPolicyDaoBuilder.withAccessPolicy)

    mockAccessPolicyDaoBuilder.build
  }

  private def buildDirectoryDao(): DirectoryDAO = {
    val mockDirectoryDaoBuilder = StatefulMockDirectoryDaoBuilder()

    maybeAllUsersGroup match {
      case Some(g) => mockDirectoryDaoBuilder.withAllUsersGroup(g)
      case None => ()
    }

    maybeWorkbenchGroup match {
      case Some(g) => mockDirectoryDaoBuilder.withWorkbenchGroup(g)
      case _ => ()
    }

    mockDirectoryDaoBuilder
      .withExistingUsers(existingUsers)
      .withEnabledUsers(enabledUsers)
      .withDisabledUsers(disabledUsers)
      .build
  }
}
