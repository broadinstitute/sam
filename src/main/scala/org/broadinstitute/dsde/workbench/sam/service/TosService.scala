package org.broadinstitute.dsde.workbench.sam.service
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, TermsOfServiceConfigResponse}
import org.broadinstitute.dsde.workbench.sam.model.{
  OldTermsOfServiceDetails,
  SamUserTos,
  TermsOfServiceComplianceStatus,
  TermsOfServiceDetails,
  TermsOfServiceHistory,
  TermsOfServiceHistoryRecord
}
import org.broadinstitute.dsde.workbench.sam.util.AsyncLogging.{FutureWithLogging, IOWithLogging}
import org.broadinstitute.dsde.workbench.sam.util.{SamRequestContext, SupportsAdmin}

import java.io.{FileNotFoundException, IOException}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.Source

class TosService(
    val cloudExtensions: CloudExtensions,
    directoryDao: DirectoryDAO,
    val tosConfig: TermsOfServiceConfig
)(
    implicit val executionContext: ExecutionContext,
    implicit val actorSystem: ActorSystem
) extends LazyLogging
    with SupportsAdmin {

  val termsOfServiceTextKey = "termsOfService"
  val privacyPolicyTextKey = "privacyPolicy"

  private val termsOfServiceUri = s"${tosConfig.baseUrl}/${tosConfig.version}/termsOfService.md"
  private val privacyPolicyUri = s"${tosConfig.baseUrl}/${tosConfig.version}/privacyPolicy.md"

  val termsOfServiceText: String = TermsOfServiceDocument(termsOfServiceUri)
  val privacyPolicyText: String = TermsOfServiceDocument(privacyPolicyUri)

  def acceptCurrentTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .acceptTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has accepted version ${tosConfig.version} of the Terms of Service")

  def rejectCurrentTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDao
      .rejectTermsOfService(userId, tosConfig.version, samRequestContext)
      .withInfoLogMessage(s"$userId has rejected version ${tosConfig.version} of the Terms of Service")

  def getTermsOfServiceConfig(): IO[TermsOfServiceConfigResponse] =
    IO.pure(
      TermsOfServiceConfigResponse(
        enforced = tosConfig.isTosEnabled,
        currentVersion = tosConfig.version,
        inGracePeriod = tosConfig.isGracePeriodEnabled,
        inRollingAcceptanceWindow = isRollingWindowInEffect()
      )
    )

  def getTermsOfServiceTexts(docSet: Set[String]): IO[String] =
    docSet match {
      case set if set.isEmpty => IO.pure(termsOfServiceText)
      case set if set.equals(Set(privacyPolicyTextKey)) => IO.pure(privacyPolicyText)
      case set if set.equals(Set(termsOfServiceTextKey)) => IO.pure(termsOfServiceText)
      case set if set.equals(Set(termsOfServiceTextKey, privacyPolicyTextKey)) => IO.pure(termsOfServiceText + "\n\n" + privacyPolicyText)
      case _ =>
        IO.raiseError(
          new WorkbenchExceptionWithErrorReport(ErrorReport(s"Docs parameters must be one of: $termsOfServiceTextKey, $privacyPolicyTextKey, or both"))
        )
    }

  private def isRollingWindowInEffect() = tosConfig.rollingAcceptanceWindowExpiration.exists(Instant.now().isBefore(_))

  @Deprecated
  def getTosDetails(samUser: SamUser, samRequestContext: SamRequestContext): IO[OldTermsOfServiceDetails] =
    directoryDao.getUserTermsOfService(samUser.id, samRequestContext).map { tos =>
      OldTermsOfServiceDetails(isEnabled = true, tosConfig.isGracePeriodEnabled, tosConfig.version, tos.map(_.version))
    }

  def getTermsOfServiceDetailsForUser(
      userId: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[TermsOfServiceDetails] =
    ensureAdminIfNeeded[TermsOfServiceDetails](userId, samRequestContext) {
      for {
        latestTermsOfServiceAcceptance <- directoryDao.getUserTermsOfService(userId, samRequestContext, Option(TosTable.ACCEPT))
        latestTermsOfServiceAction <- directoryDao.getUserTermsOfService(userId, samRequestContext)
        requestedUser <- loadUser(userId, samRequestContext)
      } yield TermsOfServiceDetails(
        latestTermsOfServiceAcceptance.map(_.version),
        latestTermsOfServiceAcceptance.map(_.createdAt),
        tosAcceptancePermitsSystemUsage(requestedUser, latestTermsOfServiceAction),
        latestTermsOfServiceAcceptance.exists(_.version.equals(tosConfig.version))
      )
    }

  private def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[SamUser] =
    directoryDao.loadUser(userId, samRequestContext).map {
      case Some(samUser) => samUser
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Could not find user:${userId}"))
    }

  def getTermsOfServiceHistoryForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext, limit: Integer): IO[TermsOfServiceHistory] =
    ensureAdminIfNeeded[TermsOfServiceHistory](userId, samRequestContext) {
      directoryDao.getUserTermsOfServiceHistory(userId, samRequestContext, limit).map {
        case samUserTosHistory if samUserTosHistory.isEmpty => TermsOfServiceHistory(List.empty)
        case samUserTosHistory =>
          TermsOfServiceHistory(
            samUserTosHistory.map(historyRecord => TermsOfServiceHistoryRecord(historyRecord.action, historyRecord.version, historyRecord.createdAt))
          )
      }
    }

  def getTermsOfServiceComplianceStatus(samUser: SamUser, samRequestContext: SamRequestContext): IO[TermsOfServiceComplianceStatus] = for {
    latestUserTos <- directoryDao.getUserTermsOfService(samUser.id, samRequestContext)
    userHasAcceptedLatestVersion = userHasAcceptedCurrentTermsOfService(latestUserTos)
    permitsSystemUsage = tosAcceptancePermitsSystemUsage(samUser, latestUserTos)
  } yield TermsOfServiceComplianceStatus(samUser.id, userHasAcceptedLatestVersion, permitsSystemUsage)

  /** If grace period enabled, don't check ToS, return true If ToS disabled, return true Otherwise return true if user has accepted ToS, or is a service account
    */
  private def tosAcceptancePermitsSystemUsage(user: SamUser, userTos: Option[SamUserTos]): Boolean = {
    if (!tosConfig.isTosEnabled) {
      return true
    }
    // Service Account users do not need to accept ToS
    val userIsServiceAccount = StandardSamUserDirectives.SAdomain.matches(user.email.value) || StandardSamUserDirectives.UAMIdomain.matches(user.email.value)
    if (userIsServiceAccount) {
      return true
    }
    if (userHasRejectedCurrentTermsOfService(userTos)) {
      return false
    }

    val userHasAcceptedCurrentVersion = userHasAcceptedCurrentTermsOfService(userTos)
    val userHasAcceptedPreviousVersion = userHasAcceptedPreviousTermsOfService(userTos)
    val userCanUseSystemUnderGracePeriod = tosConfig.isGracePeriodEnabled && userHasAcceptedPreviousVersion
    val userInsideOfRollingAcceptanceWindow = isRollingWindowInEffect() && userHasAcceptedPreviousVersion
    userHasAcceptedCurrentVersion || userInsideOfRollingAcceptanceWindow || userCanUseSystemUnderGracePeriod
  }

  private def userHasAcceptedCurrentTermsOfService(userTos: Option[SamUserTos]): Boolean =
    userTos.exists { tos =>
      tos.version.contains(tosConfig.version) && tos.action == TosTable.ACCEPT
    }

  private def userHasRejectedCurrentTermsOfService(userTos: Option[SamUserTos]): Boolean =
    userTos.exists { tos =>
      tos.version.contains(tosConfig.version) && tos.action == TosTable.REJECT
    }

  private def userHasAcceptedPreviousTermsOfService(userTosOpt: Option[SamUserTos]): Boolean =
    tosConfig.previousVersion.exists { previousTosVersion =>
      userTosOpt.exists { userTos =>
        userTos.version.contains(previousTosVersion) && userTos.action == TosTable.ACCEPT
      }
    }
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
