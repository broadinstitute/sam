package org.broadinstitute.dsde.workbench.sam

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration.Duration

/**
  * Created by dvoet on 6/27/17.
  */
trait TestSupport {
  def runAndWait[T](f: Awaitable[T]): T = Await.result(f, Duration.Inf)
}
object TestSupport extends TestSupport