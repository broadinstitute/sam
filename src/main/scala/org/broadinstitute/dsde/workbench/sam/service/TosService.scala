package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

class TosService (val directoryDao: DirectoryDAO, val appsDomain: String) extends LazyLogging {

  def createNewGroupIfNeeded(currentVersion: Int, isEnabled: Boolean): IO[Option[BasicWorkbenchGroup]] = {
    if(isEnabled) {
      getTosGroup(currentVersion).flatMap {
        case Some(_) =>
          logger.info("Returning already created group")
          IO.none
        case None =>
          logger.info("creating new ToS group")
          directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(currentVersion)),
            Set.empty, WorkbenchEmail(s"${getGroupName(currentVersion)}_GROUP@${appsDomain}")), samRequestContext = SamRequestContext(None)).map(Option(_))
      }
    } else {
      logger.info("Else being hit")
      IO.none
    }
  }

  def getGroupName(currentVersion:Int): String = {
    s"tos_accepted_${currentVersion}"
  }

  def getTosGroup(currentVersion: Int): IO[Option[BasicWorkbenchGroup]] = {
    directoryDao.loadGroup(WorkbenchGroupName(getGroupName(currentVersion)), SamRequestContext(None))
  }

}
