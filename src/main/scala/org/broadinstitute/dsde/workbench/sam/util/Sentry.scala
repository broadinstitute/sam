package org.broadinstitute.dsde.workbench.sam.util

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import io.sentry.{Hint, Sentry => SentryClient, SentryEvent, SentryOptions}
import org.broadinstitute.dsde.workbench.model.WorkbenchExceptionWithErrorReport

object Sentry extends LazyLogging {
  val sentryDsn: Option[String] = sys.env.get("SENTRY_DSN")
  // This value comes from the sbt build as the value of the version key in Version.scala
  val version: Option[String] = Option(getClass.getPackage.getImplementationVersion)

  private val statusCodesToSkip = List(
    Option(StatusCodes.BadRequest),
    Option(StatusCodes.NotFound),
    Option(StatusCodes.Forbidden),
    Option(StatusCodes.Unauthorized),
    Option(StatusCodes.Conflict)
  )

  def initSentry(): Unit = sentryDsn.fold(logger.warn("No SENTRY_DSN found, not initializing Sentry.")) { dsn =>
    val options = new SentryOptions()
    options.setDsn(dsn)
    options.setEnvironment(sys.env.getOrElse("SENTRY_ENVIRONMENT", "unknown"))
    options.setRelease(version.getOrElse("unknown"))

    options.setBeforeSend(filterException)

    SentryClient.init(options)
    logger.info("Sentry initialized")
  }

  def filterException(event: SentryEvent, hint: Hint): SentryEvent =
    event.getThrowable match {
      case workbenchException: WorkbenchExceptionWithErrorReport if statusCodesToSkip.contains(workbenchException.errorReport.statusCode) => null
      case _ => event
    }
}
