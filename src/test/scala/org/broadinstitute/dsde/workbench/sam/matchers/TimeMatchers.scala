package org.broadinstitute.dsde.workbench.sam.matchers

import org.scalatest.matchers.{MatchResult, Matcher}

import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS
import scala.concurrent.duration.{DurationInt, FiniteDuration}

trait TimeMatchers {

  class AroundMatcher(val expectedTime: Instant, giveOrTake: FiniteDuration) extends Matcher[Instant] {
    override def apply(left: Instant): MatchResult = {
      val diff = MILLIS.between(left, expectedTime)
      MatchResult(
        diff <= giveOrTake.toMillis,
        s"$left is not within $giveOrTake of $expectedTime",
        s"$left is within $giveOrTake milliseconds of $expectedTime but it was expected not to be"
      )
    }
  }

  // Instant.now() should beAround(Instant.now(), giveOrTake = 1.second)
  def beAround(expectedTime: Instant, giveOrTake: FiniteDuration = 500.milliseconds) = new AroundMatcher(expectedTime, giveOrTake)
}
