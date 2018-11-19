package org.broadinstitute.dsde.workbench.sam.util

import java.util.concurrent.TimeUnit

import cats.effect.{IO, Timer}
import com.newrelic.api.agent.NewRelic

object NewRelicMetrics {
  val prefix = "Custom/sam"

  def time[A](name: String, io: IO[A])(implicit timer: Timer[IO]): IO[A] =
    for {
      start <- timer.clock.monotonic(TimeUnit.MILLISECONDS)
      attemptedResult <- io.attempt
      end <- timer.clock.monotonic(TimeUnit.MILLISECONDS)
      duration = end - start
      _ <- attemptedResult.fold(
        _ => IO(NewRelic.recordResponseTimeMetric(s"${prefix}/${name}/failure", duration)),
        _ => IO(NewRelic.recordResponseTimeMetric(s"${prefix}/${name}/success", duration))
      )
      res <- IO.fromEither(attemptedResult)
    } yield res
}
