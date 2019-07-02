package org.broadinstitute.dsde.workbench.sam.util
import cats.effect._

import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.ExecutionContext

trait ShadowRunner {
  implicit val executionContext: ExecutionContext
  val clock: Clock[IO]

  protected def runWithShadow[T](functionName: String, real: IO[T], shadow: IO[T]): IO[T] = {
    for {
      (realResult, realTime) <- measure(real)
      _ <- measure(shadow).runAsync {
        case Left(regrets) =>
          reportShadowFailure(functionName, regrets)
          IO.unit
        case Right((shadowResult, shadowTime)) =>
          reportResult(functionName, realResult, realTime, shadowResult, shadowTime)
          IO.unit
      }.toIO
    } yield {
      realResult
    }
  }

  protected def reportShadowFailure(functionName: String, regrets: Throwable): Unit = {
    //TODO implement something useful
  }

  protected def reportResult[T](functionName: String, realResult: T, realTime: Long, shadowResult: T, shadowTime: Long): Unit = {
    //TODO implement something useful
  }

  private def measure[A](fa: IO[A]): IO[(A, Long)] = {
    for {
      start  <- clock.monotonic(MILLISECONDS)
      result <- fa
      finish <- clock.monotonic(MILLISECONDS)
    } yield (result, finish - start)
  }
}
