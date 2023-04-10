package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO

import scala.concurrent.duration.Duration

trait LastQuotaErrorDAO {
  def quotaErrorOccurredWithinDuration(duration: Duration): IO[Boolean]
  def recordQuotaError(): IO[Int]
}
