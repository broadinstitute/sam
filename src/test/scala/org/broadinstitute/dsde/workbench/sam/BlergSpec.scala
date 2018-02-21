package org.broadinstitute.dsde.workbench.sam

import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.scalatest.{FlatSpec, FreeSpec}

class BlergSpec extends FlatSpec {

  val megastring =
    """
      | toplevel = {
      | leveltwo = {
      |   thingone = {
      |     thing = one
      |   },
      |   thingtwo = {
      |     thing = two
      |   },
      |   thingthree = {
      |     thing = three
      |   }
      |   }
      | }
    """.stripMargin

  case class Thing(name: String, thing: String)

  implicit object thingReader extends ValueReader[Thing] {
    override def read(config: Config, path: String): Thing = {
      val uqPath = path.replace("\"", "")
      Thing(uqPath, config.as[String](s"$uqPath.thing"))
    }
  }

  "typesafe" should "not surprise me" in {
    val a = ConfigFactory.parseString(megastring)
    println(a.as[Map[String, Thing]]("toplevel.leveltwo").values)
  }
}
