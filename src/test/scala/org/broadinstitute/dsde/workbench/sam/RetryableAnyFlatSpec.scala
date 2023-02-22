package org.broadinstitute.dsde.workbench.sam

import org.scalatest.{Exceptional, Outcome, Retries}
import org.scalatest.flatspec.AnyFlatSpec

trait RetryableAnyFlatSpec extends AnyFlatSpec with Retries {

  val retries = 3

  override protected def withFixture(test: NoArgTest): Outcome =
    withFixture(test, 1)

  def withFixture(test: NoArgTest, count: Int): Outcome = {
    val outcome = super.withFixture(test)
    outcome match {
      case Exceptional(ex) =>
        println(s"Test '${test.name}' ${outcome.getClass.getSimpleName} on try $count.")
        println(s"Test '${test.name}' ${outcome.getClass.getSimpleName} with: $ex")
        if (count >= retries) super.withFixture(test) else withFixture(test, count + 1)
      case other => other
    }
  }
}
