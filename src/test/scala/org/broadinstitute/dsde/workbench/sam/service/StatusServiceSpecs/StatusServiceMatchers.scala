package org.broadinstitute.dsde.workbench.sam.service.StatusServiceSpecs

import org.broadinstitute.dsde.workbench.util.health.StatusCheckResponse
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.scalatest.Assertions.fail
import org.scalatest.matchers.{MatchResult, Matcher}

trait StatusServiceMatchers {
  class OKStatusMatcher() extends Matcher[StatusCheckResponse] {
    def apply(status: StatusCheckResponse): MatchResult =
      MatchResult(
        status.ok,
        s"Overall Sam status was expected to be OK, but it is not in $status",
        s"Overall Sam status was expected to NOT be OK, but it is in $status"
      )
  }

  def beOk = new OKStatusMatcher()

  class OKSubsystemMatcher(status: StatusCheckResponse) extends Matcher[Subsystem] {
    def apply(subsystem: Subsystem): MatchResult =
      status.systems.get(subsystem) match {
        case Some(subsystemStatus) =>
          MatchResult(
            subsystemStatus.ok,
            s"Expected subsystem: '$subsystem' to be OK but it is not in $status",
            s"Expected subsystem: '$subsystem' to NOT be OK but it is in $status"
          )
        case None => fail(s"Subsystem '$subsystem' is not present in $status")
      }

  }

  def beOkIn(status: StatusCheckResponse) = new OKSubsystemMatcher(status)
}
