package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.PostgresDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.lenient
import org.mockito.MockitoSugar.{mock, verify}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LivenessRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {
  val mockPostgres = mock[PostgresDirectoryDAO]
  val livenessRoutes = new LivenessRoutes(mockPostgres)

  lenient()
    .when(mockPostgres.checkStatus(any[SamRequestContext]))
    .thenReturn(IO.pure(true))

  "GET /liveness" should "give 200" in {
    eventually {
      Get("/liveness") ~> livenessRoutes.route ~> check {
        status shouldEqual OK
        verify(mockPostgres).checkStatus(any[SamRequestContext])
      }
    }
  }
}
