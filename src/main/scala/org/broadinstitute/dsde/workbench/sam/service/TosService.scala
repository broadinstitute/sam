package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.IOWithLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, SamUserTos, TermsOfServiceComplianceStatus, TermsOfServiceDetails}

import scala.concurrent.ExecutionContext
import java.io.{FileNotFoundException, IOException}
import scala.io.Source

class TosService(val directoryDao: DirectoryDAO, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  val termsOfServiceFile = s"tos/termsOfService-${tosConfig.version}.md"
  val privacyPolicyFile = s"tos/privacyPolicy-${tosConfig.version}.md"

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .acceptTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has accepted version ${tosConfig.version} of the Terms of Service")

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .rejectTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has rejected version ${tosConfig.version} of the Terms of Service")

  @Deprecated
  def getTosDetails(samUser: SamUser, samRequestContext: SamRequestContext): IO[TermsOfServiceDetails] =
    directoryDao.getUserTos(samUser.id, tosConfig.version, samRequestContext).map { tos =>
      TermsOfServiceDetails(isEnabled = true, tosConfig.isGracePeriodEnabled, tosConfig.version, tos.map(_.version))
    }

  def getTosComplianceStatus(samUser: SamUser, samRequestContext: SamRequestContext): IO[TermsOfServiceComplianceStatus] =
    directoryDao.getUserTos(samUser.id, tosConfig.version, samRequestContext).map { tos =>
      val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(tos)
      val permitsSystemUsage = tosAcceptancePermitsSystemUsage(samUser, tos)
      TermsOfServiceComplianceStatus(samUser.id, userHasAcceptedLatestVersion, permitsSystemUsage)
    }

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  private def tosAcceptancePermitsSystemUsage(user: SamUser, userTos: Option[SamUserTos]): Boolean = {
    val userIsServiceAccount = StandardSamUserDirectives.SAdomain.matches(user.email.value) // Service Account users do not need to accept ToS
    val userIsPermitted = userTos.exists { tos =>
      val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(Option(tos))
      val userCanUseSystemUnderGracePeriod = tosConfig.isGracePeriodEnabled && tos.action == TosTable.ACCEPT
      val tosDisabled = !tosConfig.isTosEnabled

      userHasAcceptedLatestVersion || userCanUseSystemUnderGracePeriod || tosDisabled

    }
    userIsPermitted || userIsServiceAccount
  }

  private def userHasAcceptedLatestTosVersion(userTos: Option[SamUserTos]): Boolean =
    userTos.exists { tos =>
      tos.version.contains(tosConfig.version) && tos.action == TosTable.ACCEPT
    }

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
