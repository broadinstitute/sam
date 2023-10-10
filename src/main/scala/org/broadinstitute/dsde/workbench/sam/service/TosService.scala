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
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, TermsOfServiceComplianceStatus, TermsOfServiceDetails}

import java.io.{FileNotFoundException, IOException}
import scala.concurrent.{Await, ExecutionContext}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.io.Source

class TosService(val directoryDao: DirectoryDAO, val tosConfig: TermsOfServiceConfig)(
    implicit val executionContext: ExecutionContext,
    implicit val actorSystem: ActorSystem
) extends LazyLogging {

  private val termsOfServiceUri = s"${tosConfig.baseUrl}/${tosConfig.version}/termsOfService.md"
  private val privacyPolicyUri = s"${tosConfig.baseUrl}/${tosConfig.version}/privacyPolicy.md"

  val termsOfServiceText = RemoteDocument(termsOfServiceUri)
  val privacyPolicyText = RemoteDocument(privacyPolicyUri)

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .acceptTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has accepted version ${tosConfig.version} of the Terms of Service")

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .rejectTermsOfService(userId, samRequestContext)
      .withInfoLogMessage(s"$userId has rejected version ${tosConfig.version} of the Terms of Service")

  @Deprecated
  def getTosDetails(samUser: SamUser): IO[TermsOfServiceDetails] =
    IO.pure(TermsOfServiceDetails(isEnabled = true, tosConfig.isGracePeriodEnabled, tosConfig.version, samUser.acceptedTosVersion))

  def getTosComplianceStatus(samUser: SamUser): IO[TermsOfServiceComplianceStatus] = {
    val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(samUser)
    val permitsSystemUsage = tosAcceptancePermitsSystemUsage(samUser)
    IO.pure(TermsOfServiceComplianceStatus(samUser.id, userHasAcceptedLatestVersion, permitsSystemUsage))
  }

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  private def tosAcceptancePermitsSystemUsage(user: SamUser): Boolean = {
    val userHasAcceptedLatestVersion = userHasAcceptedLatestTosVersion(user)
    val userCanUseSystemUnderGracePeriod = tosConfig.isGracePeriodEnabled && user.acceptedTosVersion.isDefined
    val userIsServiceAccount = StandardSamUserDirectives.SAdomain.matches(user.email.value) // Service Account users do not need to accept ToS
    val tosDisabled = !tosConfig.isTosEnabled

    userHasAcceptedLatestVersion || userCanUseSystemUnderGracePeriod || userIsServiceAccount || tosDisabled
  }

  private def userHasAcceptedLatestTosVersion(samUser: SamUser): Boolean =
    samUser.acceptedTosVersion.contains(tosConfig.version)
}

trait RemoteDocument {
  def apply(uri: String)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String
}

object RemoteDocument extends RemoteDocument with LazyLogging {
  override def apply(uri: String)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String =
    if (uri.startsWith("classpath")) {
      getTextFromResource(uri)
    } else {
      getTextFromWeb(uri)
    }

  def getTextFromWeb(uri: String)(implicit actorSystem: ActorSystem, executionContext: ExecutionContext): String = {
    val future = for {
      response <- Http().singleRequest(Get(uri))
      text <- Unmarshal(response).to[String]
    } yield text

    Await.result(future.withInfoLogMessage(s"Retrieved Terms of Service doc from $uri"), Duration.apply(10, TimeUnit.SECONDS))
  }

  def getTextFromResource(resourceUri: Uri): String = {
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
