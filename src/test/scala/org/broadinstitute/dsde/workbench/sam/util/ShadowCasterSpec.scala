package org.broadinstitute.dsde.workbench.sam.util
import cats.effect.IO
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

class ShadowCasterSpec extends FlatSpec with Matchers with ScalaFutures {

  "ShadowCaster" should "runWithShadow" in {

    class TestCaster extends ShadowCaster {
      override implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

      val real = IO.pure(10)
      val shadow = IO.timer(executionContext).sleep(1 second).map(_ => 15)

      val resultPromise: Promise[(String, Any, Long, Any, Long)] = Promise()

      def runTest(): IO[Int] = {
        runWithShadow("runTest", real, shadow)
      }

      override protected def reportResult[T](functionName: String, realResult: T, realTime: Long, shadowResult: T, shadowTime: Long): Unit = {
        resultPromise.success((functionName, realResult, realTime, shadowResult, shadowTime))
      }
    }

    val testCaster = new TestCaster()
    val result = testCaster.runTest()

    result.unsafeRunSync() should equal (10)

    implicit val patienceConfig = PatienceConfig(2 seconds)

    testCaster.resultPromise should not be 'isCompleted
    val (functionName, realResult, realTime, shadowResult, shadowTime) = testCaster.resultPromise.future.futureValue
    functionName should equal ("runTest")
    realResult should equal (10)
    realTime should be < (1000L)
    shadowResult should equal (15)
    shadowTime should be > (1000L)
  }
}
