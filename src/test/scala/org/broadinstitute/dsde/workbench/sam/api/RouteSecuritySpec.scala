package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.any
import org.mockito.internal.verification.AtLeast
import org.mockito.Mockito
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.yaml.snakeyaml.Yaml

  import scala.jdk.CollectionConverters._

/**
  * These tests verify that the apis published in swagger/api-docs.yaml call the correct SamUserDirectives.
  */
class RouteSecuritySpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {
  lazy val routesFromApiYml: Iterable[PathAndMethod] = {
    val apiDocsPath = "swagger/api-docs.yaml"
    val apiDocsStream = getClass.getClassLoader.getResourceAsStream(apiDocsPath)
    val apiDocsYaml = new Yaml().load[java.util.Map[String, Any]](apiDocsStream).asScala
    apiDocsStream.close()

    for {
      pathEntry <- apiDocsYaml("paths").asInstanceOf[java.util.Map[String, Any]].asScala
      path = pathEntry._1
      methodEntry <- pathEntry._2.asInstanceOf[java.util.Map[String, Any]].asScala
      method = methodEntry._1
    } yield PathAndMethod(path, method)
  }

  for(PathAndMethod(path, method) <- routesFromApiYml if path.startsWith("/api")) {
    s"$method $path" should "call withActiveUser" in {
      val samRoutes = Mockito.spy(TestSamRoutes(Map.empty))

      createRequest(path, method) ~> samRoutes.route ~> check {
        withClue(s"$method $path did not call withActiveUser") {
          Mockito.verify(samRoutes, new AtLeast(1)).withActiveUser(any[SamRequestContext])
        }
      }
    }
  }

  for(PathAndMethod(path, method) <- routesFromApiYml if path.startsWith("/register")) {
    s"$method $path" should "call withUserAllowInactive" in {
      val samRoutes = Mockito.spy(TestSamRoutes(Map.empty))

      createRequest(path, method) ~> samRoutes.route ~> check {
        withClue(s"$method $path did not call withUserAllowInactive") {
          Mockito.verify(samRoutes, new AtLeast(1)).withUserAllowInactive(any[SamRequestContext])
        }
      }
    }
  }

  for(PathAndMethod(path, method) <- routesFromApiYml if path.startsWith("/api/admin")) {
    s"$method $path" should "call withWorkbenchAdmin" in {
      val samRoutes = Mockito.spy(TestSamRoutes(Map.empty))

      createRequest(path, method) ~> samRoutes.route ~> check {
        withClue(s"$method $path did not call withWorkbenchAdmin") {
          Mockito.verify(samRoutes, new AtLeast(1)).asWorkbenchAdmin(any[SamUser])
        }
      }
    }
  }

  private def createRequest(path: String, method: String) = {
    method.toLowerCase match {
      case "get" => Get(path)
      case "put" => Put(path)
      case "post" => Post(path)
      case "delete" => Delete(path)
    }
  }
}

case class PathAndMethod(path: String, method: String)