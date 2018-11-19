package org.broadinstitute.dsde.workbench.sam.config
import net.ceedubs.ficus.readers.ValueReader

import scala.concurrent.duration.{Duration, FiniteDuration}

final case class DistributedLockConfig(retryInterval: FiniteDuration, maxRetry: Int)

object DistributedLockConfig {
  implicit val distributedLockConfigReader: ValueReader[DistributedLockConfig] = ValueReader.relative { config =>
    val retryInterval = config.getDuration("retryInterval")

    DistributedLockConfig(
      Duration.fromNanos(retryInterval.toNanos),
      config.getInt("maxRetry")
    )
  }
}
