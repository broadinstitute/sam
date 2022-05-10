package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig

import scala.concurrent.ExecutionContext
import java.io.{FileNotFoundException, IOException}
import scala.io.Source


class TosService (val directoryDao: DirectoryDAO, val registrationDao: RegistrationDAO, val appsDomain: String, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  val termsOfServiceFile = "termsOfService.md"

  def resetTermsOfServiceGroupsIfNeeded(samRequestContext: SamRequestContext): IO[Option[WorkbenchGroupName]] = {
    if(tosConfig.enabled) {
      getTosGroupNameIfExists(samRequestContext).flatMap {
        case None =>
          logger.info(s"creating new ToS group ${getGroupName()}")
          val groupEmail = WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@$appsDomain")
          val tosGroup = BasicWorkbenchGroup(getGroupName(tosConfig.version), Set.empty, groupEmail)
          directoryDao.createGroup(tosGroup, None, samRequestContext).handleError {
            case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode == Option(StatusCodes.Conflict) => tosGroup
          }.flatMap { createdGroup =>
            if(tosConfig.version > 0) registrationDao.disableAllHumanIdentities(samRequestContext).handleError { e =>
              // If this call has failed, then human users won't be disabled and they will not be forced to accept the
              // new ToS version to use Terra which is a compliance issue. Ensuring that human users are disabled is
              // challenging. Instead, we have set up log-based alerting to notify us of this issue so we can manually
              // disable human users (https://broadworkbench.atlassian.net/browse/WOR-280). If you must modify this
              // log message, please modify the alert as well.
              logger.error(s"Failed to disable all human identities when setting up ${getGroupName()}", e)
              throw e
            }.map { _ => Option(createdGroup.id)} //special clause for v0 ToS, which will be enforced via a migration (see WOR-118)
            else IO.pure(Option(createdGroup.id))
          }
        case groupName => IO.pure(groupName)
      }
    } else IO.none
  }

  def getGroupName(currentVersion:Int = tosConfig.version): WorkbenchGroupName = {
    WorkbenchGroupName(s"tos_accepted_${currentVersion}")
  }

  def getTosGroupNameIfExists(samRequestContext: SamRequestContext): IO[Option[WorkbenchGroupName]] = {
    val groupName = getGroupName()
    directoryDao.loadGroupEmail(groupName, samRequestContext).map(_.map(_ => groupName))
  }

  def acceptTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      resetTermsOfServiceGroupsIfNeeded(samRequestContext).flatMap {
        case Some(groupName) => directoryDao.addGroupMember(groupName, userId, samRequestContext).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName()} failed to create.")))
      }
    } else IO.pure(None)

  def rejectTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      resetTermsOfServiceGroupsIfNeeded(samRequestContext).flatMap {
        case Some(groupName) => directoryDao.removeGroupMember(groupName, userId, samRequestContext).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName()} failed to create.")))
      }
    } else IO.pure(None)

  /**
    * Check if Terms of service is enabled and if the user has accepted the latest version
    * @return IO[Some(true)] if ToS is enabled and the user has accepted
    *         IO[Some(false)] if ToS is enabled and the user hasn't accepted
    *         IO[None] if ToS is disabled
    */
  def getTosStatus(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[Boolean]] = {
    if (tosConfig.enabled) {
      directoryDao.isGroupMember(getGroupName(), userId, samRequestContext).map(Option(_))
    } else IO.none
  }

  /**
    * If grace period enabled, don't check ToS, return true
    * If ToS disabled, return true
    * Otherwise return true if user has accepted ToS
    */
  def isTermsOfServiceStatusAcceptable(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = {
    if (tosConfig.isGracePeriodEnabled) {
      IO.pure(true)
    } else {
      getTosStatus(userId, samRequestContext).map(_.getOrElse(true))
    }
  }

  /**
    * Get the terms of service text and send it to the caller
    * @return terms of service text
    */
  def getText: String = {
    val tosFileStream = try {
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
    try {
      tosFileStream.mkString
    } finally {
      tosFileStream.close
    }
  }
}
