package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ExecutionContext, Future}

object AsyncLogging extends LazyLogging {

  implicit class IOWithLogging[A](io: IO[A]) {

    def withDebugLogMessage(message: String): IO[A] = io.<*(IO(logger.debug(message)))

    def withInfoLogMessage(message: String): IO[A] = io.<*(IO(logger.info(message)))

    def withComputedInfoLogMessage(messageMapper: A => String): IO[A] = io.map { a =>
      logger.info(messageMapper.apply(a))
      a
    }

    def withWarnLogMessage(message: String): IO[A] = io.<*(IO(logger.warn(message)))

    def withErrorLogMessage(message: String): IO[A] = io.<*(IO(logger.error(message)))
  }

  implicit class FutureWithLogging[A](future: Future[A])(implicit ec: ExecutionContext) {

    def withDebugLogMessage(message: String): Future[A] = future.map { a =>
      logger.debug(message)
      a
    }

    def withInfoLogMessage(message: String): Future[A] = future.map { a =>
      logger.info(message)
      a
    }

    def withComputedInfoLogMessage(messageMapper: A => String): Future[A] = future.map { a =>
      logger.info(messageMapper.apply(a))
      a
    }

    def withWarnLogMessage(message: String): Future[A] = future.map { a =>
      logger.warn(message)
      a
    }

    def withErrorLogMessage(message: String): Future[A] = future.map { a =>
      logger.error(message)
      a
    }
  }
}
