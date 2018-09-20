package org.broadinstitute.dsde.workbench

import org.broadinstitute.dsde.workbench.model.ErrorReportSource
import java.util.concurrent.Executors

import cats.implicits._
import cats.effect.{ContextShift, IO}

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 5/18/17.
  */
package object sam {
  implicit val errorReportSource = ErrorReportSource("sam")

  private val cachedThreadPool = Executors.newCachedThreadPool()
  private val BlockingIO   = ExecutionContext.fromExecutor(cachedThreadPool)

  def runBlocking[A](io: IO[A])(implicit cs: ContextShift[IO]): IO[A] = IO.shift(BlockingIO) *> io <* IO.shift
}
