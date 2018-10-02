package org.broadinstitute.dsde.workbench.sam.util

import java.util.concurrent.Executors

import cats.effect.{IO, Resource}

import scala.concurrent.ExecutionContext

object ExecutionContexts {
  /** Resource yielding an `ExecutionContext` backed by an unbounded thread pool. */
  val blockingThreadPool: Resource[IO, ExecutionContext] = Resource(IO{
    val executor = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutor(executor)
    (ec, IO(executor.shutdown()))
  })
}
