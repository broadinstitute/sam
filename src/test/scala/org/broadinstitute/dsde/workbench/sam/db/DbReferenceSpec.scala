package org.broadinstitute.dsde.workbench.sam.db
import cats.effect.IO
import org.scalatest.{FlatSpecLike, Matchers}
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.config.AppConfig._

class DbReferenceSpec extends FlatSpecLike with Matchers {
  "DbReference" should "init" in {
    val config = ConfigFactory.load()
    val test = for {
      ref <- DbReference.resource(config.as[LiquibaseConfig]("liquibase")).use(_ => IO.unit)
    } yield {
      ref
    }

    test.unsafeRunSync()
  }
}
