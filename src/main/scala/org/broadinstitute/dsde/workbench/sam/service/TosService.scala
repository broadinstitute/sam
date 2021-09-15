package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

class TosService (val directoryDao: DirectoryDAO) extends LazyLogging {

  def createNewGroupIfNeeded(currentVersion:Int, isEnabled:Boolean): IO[Any] = {
    if(!isEnabled){
      IO.unit
    }
    else if (groupExists(currentVersion)) {
      IO.unit
    } else {
      logger.info("creating new ToS group")
      directoryDao.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(getGroupName(currentVersion)),
        Set.empty, WorkbenchEmail("foo@bar.com")), samRequestContext = SamRequestContext(None))
    }
  }

  def getGroupName(currentVersion:Int): String = {
    s"tos_accepted_${currentVersion}"
  }

  def groupExists(currentVersion:Int): Boolean = {
    val maybeGroup = directoryDao.loadGroup(WorkbenchGroupName(getGroupName(currentVersion)), SamRequestContext(None)).unsafeRunSync()
    maybeGroup.isDefined
  }

}
