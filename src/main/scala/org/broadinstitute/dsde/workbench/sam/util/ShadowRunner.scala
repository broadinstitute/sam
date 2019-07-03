package org.broadinstitute.dsde.workbench.sam.util
import java.lang.reflect.{InvocationHandler, Method, Proxy}

import cats.effect._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

case class TimedResult[T](result: Either[Throwable, T], time: FiniteDuration)

trait ShadowRunner {
  implicit val executionContext: ExecutionContext
  val clock: Clock[IO]
  val resultReporter: ShadowResultReporter

  protected def runWithShadow[T](methodCallInfo: MethodCallInfo, real: IO[T], shadow: IO[T]): IO[T] = {
    for {
      realTimedResult <- measure(real)
      _ <- measure(shadow).runAsync {
        case Left(regrets) =>
          resultReporter.reportShadowExecutionFailure(methodCallInfo, regrets)
        case Right(shadowTimedResult) =>
          resultReporter.reportResult(methodCallInfo, realTimedResult, shadowTimedResult)
      }.toIO
    } yield {
      realTimedResult.result.toTry.get
    }
  }

  private def measure[A](fa: IO[A]): IO[TimedResult[A]] = {
    for {
      start  <- clock.monotonic(MILLISECONDS)
      result <- fa.attempt
      finish <- clock.monotonic(MILLISECONDS)
    } yield TimedResult(result, FiniteDuration(finish - start, MILLISECONDS))
  }
}

trait ShadowResultReporter extends LazyLogging {
  def daoName: String

  /**
    * Called when the async machinery to invoke the shadow fails. This is not a failure in the shadow itself. This is
    * likely due to a bug.
    *
    * @param methodCallInfo
    * @param regrets
    * @return
    */
  def reportShadowExecutionFailure(methodCallInfo: MethodCallInfo, regrets: Throwable): IO[Unit] = {
    IO(logger.error(s"failure attempting to call shadow implementation of $daoName::$methodCallInfo", regrets))
  }

  /**
    * Called upon completion (not necessarily successful) of both real and shadow implementation.
    *
    * @param methodCallInfo
    * @param realTimedResult
    * @param shadowTimedResult
    * @tparam T
    * @return
    */
  def reportResult[T](methodCallInfo: MethodCallInfo, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): IO[Unit]
}

/**
  * Call me to construct an instance of your DAO that will call both a real implementation and a shadow implementation.
  * All methods in your DAO must return IO.
  */
object DaoWithShadow {
  def apply[T : ClassTag](realDAO: T, shadowDAO: T, resultReporter: ShadowResultReporter, clock: Clock[IO])(implicit executionContext: ExecutionContext): T = {
    Proxy.newProxyInstance(getClass.getClassLoader,
      Array(implicitly[ClassTag[T]].runtimeClass),
      new ShadowRunnerDynamicProxy[T](realDAO, shadowDAO, resultReporter, clock)).asInstanceOf[T]
  }
}

private class ShadowRunnerDynamicProxy[DAO](realDAO: DAO, shadowDAO: DAO, val resultReporter: ShadowResultReporter, val clock: Clock[IO])(implicit val executionContext: ExecutionContext) extends InvocationHandler with ShadowRunner {
  override def invoke(proxy: Any, method: Method, args: Array[AnyRef]): AnyRef = {
    // this will throw a class cast exception at runtime if the method does not return an IO
    // since these are IOs any side effects should be deferred so invoking them here does not do the actual work... just creates the IO
    val realIO = method.invoke(realDAO, args:_*).asInstanceOf[IO[_]]
    val shadowIO = method.invoke(shadowDAO, args:_*).asInstanceOf[IO[_]]
    runWithShadow(MethodCallInfo(method, args), realIO, shadowIO)
  }
}

case class MethodCallInfo(functionName: String, parameterNames: Array[String], parameterValues: Array[AnyRef])

object MethodCallInfo {
  def apply(method: Method, args: Array[AnyRef]): MethodCallInfo = MethodCallInfo(method.getName, method.getParameters.map(_.getName), args)
}