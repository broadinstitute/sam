package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.UserStatus
import org.scalatest.matchers.{MatchResult, Matcher}

class BeEnabledInMatcher(userStatus: UserStatus) extends Matcher[String] {
  def apply(componentName: String): MatchResult =
    userStatus.enabled.get(componentName) match {
      case Some(status) =>
        MatchResult(
          status,
          s"$componentName is not true, but expected it to be ",
          s"$componentName is true, but shouldn't be "
        )
      case None =>
        val failureMsg = s"No entry found for $componentName"
        MatchResult(false, failureMsg, failureMsg)
    }
}

object BeEnabledInMatcher {
  def beEnabledIn(userStatus: UserStatus) = new BeEnabledInMatcher(userStatus)
}