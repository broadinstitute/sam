package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.model.SamUser

import scala.concurrent.ExecutionContext
import java.io.{FileNotFoundException, IOException}
import scala.io.Source

class TosService(val directoryDao: DirectoryDAO, val appsDomain: String, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  val termsOfServiceFile = "termsOfService.md"

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      directoryDao.acceptTermsOfService(userId, tosConfig.version, samRequestContext).map(Option(_))
    } else IO.pure(None)

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      directoryDao.rejectTermsOfService(userId, samRequestContext).map(Option(_))
    } else IO.pure(None)

  /** Check if Terms of service is enabled and if the user has accepted the latest version
    * @return
    *   IO[Some(true)] if ToS is enabled and the user has accepted IO[Some(false)] if ToS is enabled and the user hasn't accepted IO[None] if ToS is disabled
    */
  def getTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      directoryDao.loadUser(userId, samRequestContext).map(_.map(_.acceptedTosVersion.contains(tosConfig.version)))
    } else IO.none

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  def isTermsOfServiceStatusAcceptable(user: SamUser): Boolean =
    tosConfig.isGracePeriodEnabled || !tosConfig.enabled || user.acceptedTosVersion.contains(tosConfig.version) || StandardSamUserDirectives.SAdomain.matches(user.email.value)

  /** Get the terms of service text and send it to the caller
    * @return
    *   terms of service text
    */
  def getText: String = {
    val tosFileStream =
      try {
        logger.debug("Reading terms of service")
        Source.fromResource(termsOfServiceFile)
      } catch {
        case e: FileNotFoundException =>
          logger.error("Terms Of Service file not found", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
        case e: IOException =>
          logger.error("Failed to read Terms of Service fail due to IO exception", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
      }
    logger.debug("Terms of service file found")
    try
      tosFileStream.mkString
    finally
      tosFileStream.close
  }
}
