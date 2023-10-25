package org.broadinstitute.dsde.workbench.sam.service
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.IOWithLogging
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.FutureWithLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.{OldTermsOfServiceDetails, SamUserTos, TermsOfServiceComplianceStatus}

import java.io.{FileNotFoundException, IOException}
import scala.concurrent.{Await, ExecutionContext}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import java.time.Instant
import scala.io.Source

class TosService(val directoryDao: DirectoryDAO, val tosConfig: TermsOfServiceConfig)(
    implicit val executionContext: ExecutionContext,
    implicit val actorSystem: ActorSystem
) extends LazyLogging {

  private val termsOfServiceUri = s"${tosConfig.baseUrl}/${tosConfig.version}/termsOfService.md"
  private val privacyPolicyUri = s"${tosConfig.baseUrl}/${tosConfig.version}/privacyPolicy.md"

  val termsOfServiceText: String = TermsOfServiceDocument(termsOfServiceUri)
  val privacyPolicyText: String = TermsOfServiceDocument(privacyPolicyUri)

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .acceptTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has accepted version ${tosConfig.version} of the Terms of Service")

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .rejectTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has rejected version ${tosConfig.version} of the Terms of Service")

  @Deprecated
  def getTosDetails(samUser: SamUser, samRequestContext: SamRequestContext): IO[OldTermsOfServiceDetails] =
    directoryDao.getUserTos(samUser.id, samRequestContext).map { tos =>
      OldTermsOfServiceDetails(isEnabled = true, tosConfig.isGracePeriodEnabled, tosConfig.version, tos.map(_.version))
    }

  def getTermsOfServiceDetails(samUserId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[SamUserTos] = {
    directoryDao.getUserTos(samUserId, samRequestContext).map {
      case Some(samUserTos) => samUserTos
      case None => return IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Could not find Terms of Service entry for user ${samUserId}")))
    }
  }

  def getTosComplianceStatus(samUser: SamUser, samRequestContext: SamRequestContext): IO[TermsOfServiceComplianceStatus] = for {
    latestUserTos <- directoryDao.getUserTos(samUser.id, samRequestContext)
    previousUserTos <- directoryDao.getUserTosVersion(samUser.id, tosConfig.previousVersion, samRequestContext)
    userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(latestUserTos)
    permitsSystemUsage = tosAcceptancePermitsSystemUsage(samUser, latestUserTos, previousUserTos)
  } yield TermsOfServiceComplianceStatus(samUser.id, userHasAcceptedLatestVersion, permitsSystemUsage)

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  private def tosAcceptancePermitsSystemUsage(user: SamUser, userTos: Option[SamUserTos], previousUserTos: Option[SamUserTos]): Boolean = {
    if (!tosConfig.isTosEnabled) {
      return true
    }
    // Service Account users do not need to accept ToS
    val userIsServiceAccount = StandardSamUserDirectives.SAdomain.matches(user.email.value)
    if (userIsServiceAccount) {
      return true
    }
    if (userHasRejectedLatestTosVersion(userTos)) {
      return false
    }
    userTos.exists { tos =>
      val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(Option(tos))
      val userCanUseSystemUnderGracePeriod = tosConfig.isGracePeriodEnabled && tos.action == TosTable.ACCEPT

      val userHasAcceptedPreviousVersion = userHasAcceptedPreviousTosVersion(previousUserTos)
      val now = Instant.now()
      val userInsideOfRollingAcceptanceWindow = tosConfig.rollingAcceptanceWindowExpiration match {
        case Some(expiration) =>
          expiration.isAfter(now) && userHasAcceptedPreviousVersion
        case None => false
      }

      userHasAcceptedLatestVersion || userInsideOfRollingAcceptanceWindow || userCanUseSystemUnderGracePeriod

    }
  }

  private def userHasAcceptedLatestTosVersion(userTos: Option[SamUserTos]): Boolean =
    userTos.exists { tos =>
      tos.version.contains(tosConfig.version) && tos.action == TosTable.ACCEPT
    }

  private def userHasRejectedLatestTosVersion(userTos: Option[SamUserTos]): Boolean =
    userTos.exists { tos =>
      tos.version.contains(tosConfig.version) && tos.action == TosTable.REJECT
    }

  private def userHasAcceptedPreviousTosVersion(previousUserTos: Option[SamUserTos]): Boolean =
    previousUserTos.exists(tos => tos.action == TosTable.ACCEPT)
}

trait TermsOfServiceDocument {
  def apply(uri: Uri)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String
}

object TermsOfServiceDocument extends TermsOfServiceDocument with LazyLogging {
  override def apply(uri: Uri)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String =
    if (uri.scheme.equalsIgnoreCase("classpath")) {
      getTextFromResource(uri)
    } else {
      getTextFromWeb(uri)
    }

  /** Get the contents of an HTTP resource.
    * @param uri
    *   HTTP(s) URI of a resource
    * @return
    *   The text of the document at the provided uri.
    */
  private def getTextFromWeb(uri: Uri)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String = {
    val future = for {
      response <- Http().singleRequest(Get(uri))
      text <- Unmarshal(response).to[String]
    } yield text

    Await.result(future.withInfoLogMessage(s"Retrieved Terms of Service doc from $uri"), Duration.apply(10, TimeUnit.SECONDS))
  }

  /** Get the contents of a resource on the classpath. Used for BEE Environments
    * @param resourceUri
    *   classpath resource uri
    * @return
    *   The text of a document in the classpath
    */
  private def getTextFromResource(resourceUri: Uri): String = {
    val fileStream =
      try {
        logger.debug(s"Reading $resourceUri")
        Source.fromResource(resourceUri.path.toString())
      } catch {
        case e: FileNotFoundException =>
          logger.error(s"$resourceUri file not found", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
        case e: IOException =>
          logger.error(s"Failed to read $resourceUri file due to IO exception", e)
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, e))
      }
    logger.debug(s"$resourceUri file found")
    try
      fileStream.mkString
    finally
      fileStream.close
  }
}
