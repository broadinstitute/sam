package org.broadinstitute.dsde.workbench.sam.provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.Boot.createAppDependencies
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.appConfig
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import pact4s.provider._
import pact4s.scalatest.PactVerifier

import java.io.File
import scala.concurrent.duration.DurationInt

class SamProviderSpec extends AnyFlatSpec with ScalatestRouteTest with TestSupport with BeforeAndAfterAll with PactVerifier {
  def startSam(): Unit = {
    val appDependencies = createAppDependencies(appConfig)
    appDependencies.use { dependencies => // this is where the resource is used
      for {
        _ <- dependencies.samApplication.resourceService.initResourceTypes()

        _ <- dependencies.policyEvaluatorService.initPolicy()

        _ <- dependencies.cloudExtensionsInitializer.onBoot(dependencies.samApplication)

        binding <- IO.fromFuture(IO(Http().newServerAt("0.0.0.0", 8080).bind(dependencies.samRoutes.route)))
        _ <- IO.fromFuture(IO(binding.whenTerminated))
        _ <- IO(system.terminate())
      } yield ()
    }
  }

  override def beforeAll(): Unit = {
    super.beforeAll()
    startSam()
  }

  val provider: ProviderInfoBuilder = ProviderInfoBuilder(
    "scalatest-provider",
    PactSource.FileSource(
      Map("scalatest-consumer" -> new File("src/test/resources/pact-provider.json"))
    )
  )
    .withHost("localhost")
    .withPort(8080)

  it should "Verify pacts" in {
    verifyPacts(
      publishVerificationResults = None,
      providerVerificationOptions = Nil,
      verificationTimeout = Some(10.seconds)
    )
  }

}
