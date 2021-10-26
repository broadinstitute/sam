package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.io.{FileNotFoundException, IOException}
import scala.io.Source

class TosService (val directoryDao: DirectoryDAO, val appsDomain: String) extends LazyLogging {
  val termsOfServiceFile = "termsOfService.md"

  def createNewGroupIfNeeded(currentVersion: Int, isEnabled: Boolean): IO[Option[BasicWorkbenchGroup]] = {
    if(isEnabled) {
      getTosGroup(currentVersion).flatMap {
        case Some(_) =>
          IO.none
        case None =>
          logger.info("creating new ToS group")
          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(currentVersion)),
            Set.empty, WorkbenchEmail(s"GROUP_${getGroupName(currentVersion)}@${appsDomain}")), samRequestContext = SamRequestContext(None)).map(Option(_))
      }
    } else
      IO.none
  }

  def getGroupName(currentVersion:Int): String = {
    s"tos_accepted_${currentVersion}"
  }

  def getTosGroup(currentVersion: Int): IO[Option[BasicWorkbenchGroup]] = {
    directoryDao.loadGroup(WorkbenchGroupName(getGroupName(currentVersion)), SamRequestContext(None))
  }

  def getTosStatus(currentVersion: Int, user: WorkbenchSubject): IO[Boolean] = {
    for {
      group <- directoryDao.loadGroup(WorkbenchGroupName(getGroupName(currentVersion)), SamRequestContext(None))
      isGroupMember <- directoryDao.isGroupMember(group.id, user, SamRequestContext(None))
    } yield isGroupMember
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
