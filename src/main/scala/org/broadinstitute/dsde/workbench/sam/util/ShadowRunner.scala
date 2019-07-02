package org.broadinstitute.dsde.workbench.sam.util
import cats.effect._

import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.ExecutionContext

trait ShadowRunner {
  implicit val executionContext: ExecutionContext
  val clock: Clock[IO]

  protected def runWithShadow[T](functionName: String, real: IO[T], shadow: IO[T]): IO[T] = {
    for {
      realTimedResult <- measure(real)
      _ <- measure(shadow).runAsync {
        case Left(regrets) =>
          reportShadowFailure(functionName, regrets)
          IO.unit
        case Right(shadowTimedResult) =>
          reportResult(functionName, realTimedResult, shadowTimedResult)
          IO.unit
      }.toIO
    } yield {
      realTimedResult.result
    }
  }

  protected def reportShadowFailure(functionName: String, regrets: Throwable): Unit = {
    //TODO implement something useful
  }

  protected def reportResult[T](functionName: String, realTimedResult: TimedResult[T], shadowTimedResult: TimedResult[T]): Unit = {
    //TODO implement something useful
  }

  private def measure[A](fa: IO[A]): IO[TimedResult[A]] = {
    for {
      start  <- clock.monotonic(MILLISECONDS)
      result <- fa
      finish <- clock.monotonic(MILLISECONDS)
    } yield TimedResult(result, finish - start)
  }
}

case class TimedResult[T](result: T, time: Long)
