package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.concurrent.Semaphore
import cats.effect.{Clock, ContextShift, IO, Timer}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._

class ShadowRunnerSpec extends FlatSpec with Matchers with ScalaFutures {

  "ShadowRunner" should "run real dao and not wait for shadow to complete" in {
    val methodCallInfo = MethodCallInfo("runTest", Array.empty, Array.empty)
    val testShadowResultReporter = new TestShadowResultReporter

    /**
      * TestShadowRunner creates a real and a shadow IO. The real one completes immediately. The shadow waits
      * for a semaphore to be released. The semaphore is acquired when runTest is called. The shadow will try
      * to acquire it again and will have to wait until it is release externally.
      */
    class TestShadowRunner extends ShadowRunner {
      implicit val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global
      override implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
      val semaphore = Semaphore[IO](1).unsafeRunSync()

      override val clock: Clock[IO] = Clock.create[IO]
      override val resultReporter: ShadowResultReporter = testShadowResultReporter

      val real = IO.pure(10)
      val shadow = semaphore.acquire.map(_ => 15)

      def runTest(): IO[Int] = {
        for {
          _ <- semaphore.acquire
          result <- runWithShadow(methodCallInfo, real, shadow)
        } yield result

      }
    }

    val testShadowRunner = new TestShadowRunner()
    val result = testShadowRunner.runTest()

    result.unsafeRunSync() should equal (10)

    implicit val patienceConfig = PatienceConfig(2 seconds)

    testShadowResultReporter.resultFuture should not be 'isCompleted
    testShadowRunner.semaphore.release.unsafeRunSync()
    val (actualMethodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    actualMethodCallInfo should equal (methodCallInfo)
    realTimedResult.result should equal (Right(10))
    shadowTimedResult.result should equal (Right(15))
  }

  it should "run real dao and not be bothered by shadow failure" in {
    val methodCallInfo = MethodCallInfo("runTest", Array.empty, Array.empty)
    val testShadowResultReporter = new TestShadowResultReporter

    class TestShadowRunner extends ShadowRunner {
      override implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
      override val clock: Clock[IO] = Clock.create[IO]
      override val resultReporter: ShadowResultReporter = testShadowResultReporter

      val real = IO.pure(10)
      val shadow = IO.raiseError(new Exception("shadow boom"))

      def runTest(): IO[Int] = runWithShadow(methodCallInfo, real, shadow)
    }

    val testShadowRunner = new TestShadowRunner()
    val result = testShadowRunner.runTest()

    result.unsafeRunSync() should equal (10)

    val (actualMethodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    actualMethodCallInfo should equal (methodCallInfo)
    realTimedResult.result should equal (Right(10))
    shadowTimedResult.result should be ('isLeft)
  }

  "ShadowRunnerDynamicProxy" should "proxy calls to both real and shadow dao" in {
    val testShadowResultReporter = new TestShadowResultReporter
    trait TestDAO {
      def test(x: Int): IO[Int]
    }
    class RealDAO extends TestDAO {
      def test(x: Int) = IO.pure(x+1)
    }
    class ShadowDAO extends TestDAO {
      def test(x: Int) = IO.pure(x-1)
    }

    implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)
    implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)
    val clock: Clock[IO] = Clock.create[IO]
    val proxy = DaoWithShadow(new RealDAO, new ShadowDAO, testShadowResultReporter, clock)

    val realResult = proxy.test(20).unsafeRunSync()
    realResult should be (21)
    val (methodCallInfo, realTimedResult, shadowTimedResult) = testShadowResultReporter.resultFuture.futureValue
    methodCallInfo.functionName should equal ("test")
    methodCallInfo.parameterNames should contain theSameElementsInOrderAs (Array("x"))
    methodCallInfo.parameterValues should contain theSameElementsInOrderAs (Array(20.asInstanceOf[AnyRef]))
    realTimedResult.result should equal (Right(21))
    shadowTimedResult.result should equal (Right(19))
  }
}

/**
  * Reports results for test purposes via a Future
  */
class TestShadowResultReporter extends ShadowResultReporter {
  private val resultPromise: Promise[(MethodCallInfo, TimedResult[_], TimedResult[_])] = Promise()
  /**
    * use this future to be notified when results are reported
    */
  val resultFuture: Future[(MethodCallInfo, TimedResult[_], TimedResult[_])] = resultPromise.future

  override val daoName: String = "testDAO"

  override def reportResult[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): IO[Unit] = {
    resultPromise.success((methodCallInfo, realTimedResult, shadowTimedResult))
    IO.unit
  }

  override def reportShadowExecutionFailure(methodCallInfo: MethodCallInfo, regrets: Throwable): IO[Unit] = {
    resultPromise.failure(regrets)
    IO.unit
  }
}