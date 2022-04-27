package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName, WorkbenchSubject}
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
      getTosGroupName(samRequestContext).flatMap {
        case None =>
          logger.info(s"creating new ToS group ${getGroupNameString()}")
          val groupEmail = WorkbenchEmail(s"GROUP_${getGroupNameString(tosConfig.version)}@$appsDomain")
          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupNameString(tosConfig.version)), Set.empty, groupEmail), None, samRequestContext).flatMap { createdGroup =>
            if(tosConfig.version > 0) registrationDao.disableAllHumanIdentities(samRequestContext).map { _ => Option(createdGroup.id)} //special clause for v0 ToS, which will be enforced via a migration (see WOR-118)
            else IO.pure(Option(createdGroup.id))
          }
        case groupName => IO.pure(groupName)
      }
    } else IO.none
  }

  def getGroupNameString(currentVersion:Int = tosConfig.version): String = {
    s"tos_accepted_${currentVersion}"
  }

  def getTosGroupName(samRequestContext: SamRequestContext): IO[Option[WorkbenchGroupName]] = {
    val groupName = WorkbenchGroupName(getGroupNameString())
    directoryDao.loadGroupEmail(groupName, samRequestContext).map(_.map(_ => groupName))
  }

  def acceptTosStatus(user: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      resetTermsOfServiceGroupsIfNeeded(samRequestContext).flatMap {
        case Some(groupName) => directoryDao.addGroupMember(groupName, user, samRequestContext).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupNameString()} failed to create.")))
      }
    } else IO.pure(None)

  def rejectTosStatus(user: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[Boolean]] =
    if (tosConfig.enabled) {
      resetTermsOfServiceGroupsIfNeeded(samRequestContext).flatMap {
        case Some(groupName) => directoryDao.removeGroupMember(groupName, user, samRequestContext).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupNameString()} failed to create.")))
      }
    } else IO.pure(None)

  /**
    * Check if Terms of service is enabled and if the user has accepted the latest version
    * @return IO[Some(true)] if ToS is enabled and the user has accepted
    *         IO[Some(false)] if ToS is enabled and the user hasn't accepted
    *         IO[None] if ToS is disabled
    */
  def getTosStatus(user: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[Boolean]] = {
    if (tosConfig.enabled) {
      getTosGroupName(samRequestContext).flatMap {
        case Some(groupName) => directoryDao.isGroupMember(groupName, user, samRequestContext).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupNameString()} not found.")))
      }
    } else IO.none
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
