package org.broadinstitute.dsde.workbench.sam.util

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class Config2Spec extends AnyFreeSpec with Matchers {
  "Config 2 test" - {
    "loads connectionPoolSize" in {
      val config = ConfigFactory.load()
      config.getString("fruits.banana") shouldBe "yellow"

    }
  }
}
