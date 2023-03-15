package org.broadinstitute.dsde.workbench.sam.service.StatusServiceSpecs

import org.broadinstitute.dsde.workbench.util.health.StatusCheckResponse
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Subsystem
import org.scalatest.matchers.{MatchResult, Matcher}

trait StatusServiceMatchers {
  class OKStatusMatcher() extends Matcher[StatusCheckResponse] {
    def apply(status: StatusCheckResponse): MatchResult =
      MatchResult(
        status.ok,
        "Overall Sam status was expected to be OK, but it is not",
        "Overall Sam status was expected to not be OK, but it is")
  }

  def beOk = new OKStatusMatcher()

  class OKSubsystemMatcher(status: StatusCheckResponse) extends Matcher[Subsystem] {
    def apply(subsystem: Subsystem): MatchResult =
      MatchResult(
        status.ok,
        s"Expected system: '$subsystem' to be OK but it is not",
        s"Expected system: '$subsystem' to NOT be OK but it is")
  }

  def beOkIn(status: StatusCheckResponse) = new OKSubsystemMatcher(status)
}
