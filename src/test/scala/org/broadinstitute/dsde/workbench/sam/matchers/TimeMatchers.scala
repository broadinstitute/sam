package org.broadinstitute.dsde.workbench.sam.matchers

import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher, MatchResult, Matcher}

import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS
import scala.concurrent.duration.{DurationInt, FiniteDuration}

trait TimeMatchers {

  class AroundMatcher(expectedTime: Instant, giveOrTake: FiniteDuration) extends Matcher[Instant] {
    override def apply(left: Instant): MatchResult = {
      val diff = MILLIS.between(left, expectedTime)
      MatchResult(
        diff <= giveOrTake.toMillis,
        s"$left is not within $giveOrTake of $expectedTime",
        s"$left is within $giveOrTake milliseconds of $expectedTime but it was expected not to be"
      )
    }
  }

  def beAround(expectedTime: Instant, giveOrTake: FiniteDuration = 100.milliseconds) = new AroundMatcher(expectedTime, giveOrTake)

  def createdAt(expectedInstant: Instant): HavePropertyMatcher[SamUser, Instant] =
    (user: SamUser) => HavePropertyMatchResult(
      user.createdAt == expectedInstant,
      "createdAt",
      expectedInstant,
      user.createdAt
    )
}
