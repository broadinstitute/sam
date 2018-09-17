package org.broadinstitute.dsde.workbench.sam.config

import cats.data.NonEmptyList
import org.scalatest.{FlatSpec, Matchers}
import com.typesafe.config._
import net.ceedubs.ficus.Ficus._

class NonEmptyListConfigReaderSpec extends FlatSpec with Matchers {
  "NonEmptyListConfigReader" should "read missing list" in {
    val c = ConfigFactory.empty()
    val test = c.as[Option[NonEmptyList[String]]]("test")
    test should equal(None)
  }

  it should "read empty list" in {
    val c = ConfigFactory.parseString("test = []")
    val test = c.as[Option[NonEmptyList[String]]]("test")
    test should equal(None)
  }

  it should "read non empty list" in {
    val c = ConfigFactory.parseString("test = [foo, bar]")
    val test = c.as[Option[NonEmptyList[String]]]("test")
    test should equal(Some(NonEmptyList.of("foo", "bar")))
  }

}
