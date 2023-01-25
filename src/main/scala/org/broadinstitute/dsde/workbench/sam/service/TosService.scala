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
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, TermsOfServiceAdherenceStatus, TermsOfServiceDetails}

import scala.concurrent.ExecutionContext
import java.io.{FileNotFoundException, IOException}
import scala.io.Source

class TosService(val directoryDao: DirectoryDAO, val appsDomain: String, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  val termsOfServiceFile = s"tos/termsOfService-${tosConfig.version}.md"
  val privacyPolicyFile = s"tos/privacyPolicy-${tosConfig.version}.md"

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao.acceptTermsOfService(userId, tosConfig.version, samRequestContext)

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao.rejectTermsOfService(userId, samRequestContext)

  // This method will disappear once UI is using getTosAdherenceDetails
  def getTosDetails(samUser: SamUser): IO[TermsOfServiceDetails] =
    IO.pure(TermsOfServiceDetails(isEnabled = true, tosConfig.isGracePeriodEnabled, tosConfig.version, samUser.acceptedTosVersion))

  def getTosAdherenceStatus(samUser: SamUser): IO[TermsOfServiceAdherenceStatus] = {
    val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(samUser)
    val canUseTerra = tosAcceptanceAllowsTerraUsage(samUser)
    IO.pure(TermsOfServiceAdherenceStatus(samUser.id, userHasAcceptedLatestVersion, canUseTerra))
  }

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  private def tosAcceptanceAllowsTerraUsage(user: SamUser): Boolean =
    (tosConfig.isGracePeriodEnabled && user.acceptedTosVersion.isDefined) || // There is a grace period, and the user has accepted some form of the ToS
      userHasAcceptedLatestTosVersion(user) || // No grace period, but user has accepted the current ToS version
      StandardSamUserDirectives.SAdomain.matches(user.email.value) // The user is a Service Account

  private def userHasAcceptedLatestTosVersion(samUser: SamUser): Boolean =
    samUser.acceptedTosVersion.contains(tosConfig.version)

  /** Get the terms of service text and send it to the caller
    * @return
    *   terms of service text
    */
  def getText(file: String, prettyTitle: String): String = {
    val fileStream =
      try {
        logger.debug(s"Reading $prettyTitle")
        Source.fromResource(file)
      } catch {
        case e: FileNotFoundException =>
          logger.error(s"$prettyTitle file not found", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
        case e: IOException =>
          logger.error(s"Failed to read $prettyTitle file due to IO exception", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
      }
    logger.debug(s"$prettyTitle file found")
    try
      fileStream.mkString
    finally
      fileStream.close
  }

  def getPrivacyText: String =
    getText(privacyPolicyFile, "Privacy Policy")

  def getTosText: String =
    getText(termsOfServiceFile, "Terms of Service")
}
