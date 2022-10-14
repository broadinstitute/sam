package org.broadinstitute.dsde.workbench.sam.config

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFreeSpec with Matchers {
  "AppConfig " - {
    "loads itself from files" in {
      val appConfig = AppConfig.load(AppConfig.CONFIG_FROM_FILES)
      appConfig.emailDomain shouldBe "dev.test.firecloud.org"
    }

    "loads itself from environment variables" in {
      // sys.env["emailDomain"] = "banana.com"
      val foo = sys.env
      val appConfig = AppConfig.load(AppConfig.CONFIG_FROM_ENV)
      appConfig.emailDomain shouldBe "banana.com"
    }
  }
}
