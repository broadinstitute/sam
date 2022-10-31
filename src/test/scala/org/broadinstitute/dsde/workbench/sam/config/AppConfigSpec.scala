package org.broadinstitute.dsde.workbench.sam.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AppConfigSpec extends AnyFlatSpec with Matchers {
  "AppConfig" should "fallback to sam.conf if no environment variable is defined" in {

    val appConfig = AppConfig.load
    appConfig.prometheusConfig.endpointPort should be(1)
  }
}
