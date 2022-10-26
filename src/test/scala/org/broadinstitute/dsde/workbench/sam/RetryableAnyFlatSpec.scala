package org.broadinstitute.dsde.workbench.sam

import org.scalatest.{Canceled, Failed, Outcome, Retries}
import org.scalatest.flatspec.AnyFlatSpec

trait RetryableAnyFlatSpec extends AnyFlatSpec with Retries {

  val retries = 3

  override def withFixture(test: NoArgTest): Outcome =
    withFixture(test, 1)

  def withFixture(test: NoArgTest, count: Int): Outcome = {
    val outcome = super.withFixture(test)
    outcome match {
      case Failed(_) | Canceled(_) =>
        println(s"Test '${test.name}' failed on try $count.")
        if (count >= retries) super.withFixture(test) else withFixture(test, count + 1)
      case other => other
    }
  }
}
