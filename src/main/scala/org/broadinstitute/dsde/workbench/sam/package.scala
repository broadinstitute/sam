package org.broadinstitute.dsde.workbench

import org.broadinstitute.dsde.workbench.model.ErrorReportSource
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 5/18/17.
  */
package object sam {
  implicit val errorReportSource = ErrorReportSource("sam")

  private val cachedThreadPool = Executors.newCachedThreadPool()
  val BlockingIO   = ExecutionContext.fromExecutor(cachedThreadPool)
}
