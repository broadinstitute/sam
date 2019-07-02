package org.broadinstitute.dsde.workbench.sam.util
import cats.effect._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.MILLISECONDS
import scala.concurrent.{ExecutionContext, Future}

trait ShadowCaster extends LazyLogging {
  implicit val executionContext: ExecutionContext

  protected def runWithShadow[T](functionName: String, real: IO[T], shadow: IO[T]): IO[T] = {

    val realFuture = measure(real).unsafeToFuture()
    val shadowFuture = measure(shadow).unsafeToFuture()

    Future.reduceLeft[(T, Long), (T, Long)](List(realFuture, shadowFuture)) { case ((realResult, realTime), (shadowResult, shadowTime)) =>
      reportResult(functionName, realResult, realTime, shadowResult, shadowTime)
      (realResult, realTime)
    }

    IO.fromFuture(IO(realFuture)).map(_._1)
  }

  protected def reportResult[T](functionName: String, realResult: T, realTime: Long, shadowResult: T, shadowTime: Long) = {
    val eq = realResult != shadowResult
    val timeDiff = shadowTime - realTime

    logger.debug(s"$functionName, equal: $eq, time increase $timeDiff")
  }

  private def measure[A](fa: IO[A]): IO[(A, Long)] = {
    val clock = Clock.create[IO]
    for {
      start  <- clock.monotonic(MILLISECONDS)
      result <- fa
      finish <- clock.monotonic(MILLISECONDS)
    } yield (result, finish - start)
  }
}
