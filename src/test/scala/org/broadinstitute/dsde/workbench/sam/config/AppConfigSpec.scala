package org.broadinstitute.dsde.workbench.sam.config

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFreeSpec with Matchers {
  "AppConfig " - {
    "loads itself from hocon configs" in {
      val config = ConfigFactory.load()
      val appConfig = AppConfig.loadFromHoconConfig(config)
      appConfig.emailDomain shouldBe "dev.test.firecloud.org"
    }

    "loads itself from environment variables" in {
      val env: Map[String, String] = Map("fruit" -> "banana")
      val appConfig = AppConfig.loadFromMap(env)
      appConfig.emailDomain shouldBe "banana.com"
    }
  }
}
