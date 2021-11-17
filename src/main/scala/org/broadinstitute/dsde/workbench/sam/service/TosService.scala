package org.broadinstitute.dsde.workbench.sam

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName, WorkbenchSubject}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig

import scala.concurrent.ExecutionContext

import java.io.{FileNotFoundException, IOException}
import scala.io.Source


class TosService (val directoryDao: DirectoryDAO, val appsDomain: String, val tosConfig: TermsOfServiceConfig)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  val termsOfServiceFile = "termsOfService.md"

  def createNewGroupIfNeeded(): IO[Option[BasicWorkbenchGroup]] = {
    if(tosConfig.enabled) {
      getTosGroup().flatMap {
        case None =>
          logger.info("creating new ToS group")
          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(tosConfig.version)),
            Set.empty, WorkbenchEmail(s"GROUP_${getGroupName(tosConfig.version)}@${appsDomain}")), samRequestContext = SamRequestContext(None)).map(Option(_))
        case group => IO.pure(group)
      }
    } else
      IO.none
  }

  def getGroupName(currentVersion:Int): String = {
    s"tos_accepted_${currentVersion}"
  }

  def getTosGroup(): IO[Option[BasicWorkbenchGroup]] = {
    directoryDao.loadGroup(WorkbenchGroupName(getGroupName(tosConfig.version)), SamRequestContext(None))
  }

  def acceptTosStatus(user: WorkbenchSubject): IO[Boolean] = {
    if (tosConfig.enabled) {
      createNewGroupIfNeeded().flatMap {
        case Some(group) => directoryDao.addGroupMember(group.id, user, SamRequestContext(None))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName(tosConfig.version)} failed to create.")))
      }
    } else IO.pure(false)
  }

  def getTosStatus(user: WorkbenchSubject): IO[Option[Boolean]] = {
    if (tosConfig.enabled) {
      createNewGroupIfNeeded().flatMap {
        case Some(group) => directoryDao.isGroupMember(group.id, user, SamRequestContext(None)).map(Option(_))
        case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Terms of Service group ${getGroupName(tosConfig.version)} not found.")))
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
