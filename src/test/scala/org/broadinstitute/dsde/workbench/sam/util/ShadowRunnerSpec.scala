package org.broadinstitute.dsde.workbench.sam.util
import cats.effect.{Clock, IO}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

class ShadowRunnerSpec extends FlatSpec with Matchers with ScalaFutures {

  "ShadowCaster" should "runWithShadow" in {

    class TestRunner extends ShadowRunner {
      override implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
      override implicit val clock: Clock[IO] = Clock.create[IO]

      val real = IO.pure(10)
      val shadow = IO.timer(executionContext).sleep(1 second).map(_ => 15)

      val resultPromise: Promise[(String, TimedResult[_], TimedResult[_])] = Promise()

      def runTest(): IO[Int] = {
        runWithShadow("runTest", real, shadow)
      }

      override protected def reportResult[T](functionName: String, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): Unit = {
        resultPromise.success((functionName, realTimedResult, shadowTimedResult))
      }
    }

    val testCaster = new TestRunner()
    val result = testCaster.runTest()

    result.unsafeRunSync() should equal (10)

    implicit val patienceConfig = PatienceConfig(2 seconds)

    testCaster.resultPromise should not be 'isCompleted
    val (functionName, realTimedResult, shadowTimedResult) = testCaster.resultPromise.future.futureValue
    functionName should equal ("runTest")
    realTimedResult.result should equal (10)
    realTimedResult.time should be < (1000L)
    shadowTimedResult.result should equal (15)
    shadowTimedResult.time should be > (1000L)
  }
}
