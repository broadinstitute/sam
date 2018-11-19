package org.broadinstitute.dsde.workbench.sam.util

import java.util.concurrent.Executors

import cats.effect.{IO, Resource}

import scala.concurrent.ExecutionContext

object ExecutionContexts {

  /**
    * Resource yielding an `ExecutionContext` backed by an unbounded thread pool.
    *
    * Any blocking code should be running on this ExecutionContext
    * For more info: https://gist.github.com/djspiewak/46b543800958cf61af6efa8e072bfd5c
    */
  val blockingThreadPool: Resource[IO, ExecutionContext] = Resource(IO {
    val executor = Executors.newCachedThreadPool()
    val ec = ExecutionContext.fromExecutor(executor)
    (ec, IO(executor.shutdown()))
  })
}
