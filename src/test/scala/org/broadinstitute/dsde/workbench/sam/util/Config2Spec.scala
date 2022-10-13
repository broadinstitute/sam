package org.broadinstitute.dsde.workbench.sam.util

import com.typesafe.config.ConfigFactory
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.io.File

class Config2Spec extends AnyFreeSpec with Matchers {
  "Config 2 test" - {
    "loads fruits.bananas" in {
      val config = ConfigFactory.load()
      config.getString("fruits.banana") shouldBe "yellow"
    }

    "loads variable from sam2.conf when the variable is present in both" in {
      val config = ConfigFactory.defaultReference()

      config.getString("fruits.banana") shouldBe "yellow"
      val file = new File("src/test/resources/sam2.conf")
      val appConfig = ConfigFactory.parseFile(file)

      appConfig.getString("fruits.banana") shouldBe "red"

      val generalConfig = ConfigFactory.load(appConfig)
      generalConfig.getString("fruits.banana") shouldBe "red"

    }
  }
}
