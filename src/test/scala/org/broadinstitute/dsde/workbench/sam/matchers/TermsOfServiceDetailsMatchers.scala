package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceDetails
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS

//latestAcceptedVersion: String, acceptedOn: Instant, permitsSystemUsage: Boolean
trait TermsOfServiceDetailsMatchers {
  def latestAcceptedVersion(expectedValue: String): HavePropertyMatcher[TermsOfServiceDetails, String] =
    new HavePropertyMatcher[TermsOfServiceDetails, String] {
      def apply(termsOfServiceDetails: TermsOfServiceDetails): HavePropertyMatchResult[String] =
        HavePropertyMatchResult(
          termsOfServiceDetails.latestAcceptedVersion == expectedValue,
          "latestAcceptedVersion",
          expectedValue,
          termsOfServiceDetails.latestAcceptedVersion
        )
    }

  def acceptedOn(expectedValue: Instant): HavePropertyMatcher[TermsOfServiceDetails, Instant] =
    new HavePropertyMatcher[TermsOfServiceDetails, Instant] {
      def apply(termsOfServiceDetails: TermsOfServiceDetails): HavePropertyMatchResult[Instant] = {
        val diff = MILLIS.between(termsOfServiceDetails.acceptedOn, expectedValue)
        HavePropertyMatchResult(
          diff < 1000,
          "acceptedOn",
          expectedValue,
          termsOfServiceDetails.acceptedOn
        )
      }
    }

  def permitsSystemUsage(expectedValue: Boolean): HavePropertyMatcher[TermsOfServiceDetails, Boolean] =
    new HavePropertyMatcher[TermsOfServiceDetails, Boolean] {
      def apply(termsOfServiceDetails: TermsOfServiceDetails): HavePropertyMatchResult[Boolean] = {
        HavePropertyMatchResult(
          expectedValue == termsOfServiceDetails.permitsSystemUsage,
          "permitsSystemUsage",
          expectedValue,
          termsOfServiceDetails.permitsSystemUsage
        )
      }
    }
}
