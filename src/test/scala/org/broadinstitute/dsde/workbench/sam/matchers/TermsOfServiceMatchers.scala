package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceDetails
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import java.time.Instant

/**
 * Example usage for Property Matchers:
 *
 * responseAs[TermsOfServiceDetails] should have (
 *   latestAcceptedVersion ("v2"),
 *   acceptedOn (Instant.now),
 *   permitSystemUsage (true)
 * )
 */
trait TermsOfServiceMatchers {
  def latestAcceptedVersion(expectedValue: String): HavePropertyMatcher[TermsOfServiceDetails, String] =
    (left: TermsOfServiceDetails) => new HavePropertyMatchResult(
      left.latestAcceptedVersion == expectedValue,
      "latestAcceptedVersion",
      expectedValue,
      left.latestAcceptedVersion
    )

  def acceptedOn(expectedValue: Instant): HavePropertyMatcher[TermsOfServiceDetails, Instant] =
    (left: TermsOfServiceDetails) => new HavePropertyMatchResult(
      left.acceptedOn == expectedValue,
      "acceptedOn",
      expectedValue,
      left.acceptedOn
    )

  def permitSystemUsage(expectedValue: Boolean): HavePropertyMatcher[TermsOfServiceDetails, Boolean] =
    (left: TermsOfServiceDetails) => new HavePropertyMatchResult(
      left.permitSystemUsage == expectedValue,
      "permitSystemUsage",
      expectedValue,
      left.permitSystemUsage
    )
}
