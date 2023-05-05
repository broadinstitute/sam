package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.AccessPolicy
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.ExecutionContext

case class TestPolicyEvaluatorServiceBuilder(policyDAO: AccessPolicyDAO, directoryDAO: DirectoryDAO)(implicit val executionContext: ExecutionContext, val openTelemetry: OpenTelemetryMetrics[IO]) extends MockitoSugar {
  private var policies: Map[WorkbenchGroupIdentity, WorkbenchGroup] = Map()

  val mockPolicyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)

  def withPolicy(policy: AccessPolicy): TestPolicyEvaluatorServiceBuilder = {
    policies += (policy.id -> policy)
    this
  }
  def build: PolicyEvaluatorService = mockPolicyEvaluatorService
}
