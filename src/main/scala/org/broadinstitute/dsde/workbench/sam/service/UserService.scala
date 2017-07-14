package org.broadinstitute.dsde.workbench.sam.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 7/14/17.
  */
class UserService(val directoryDAO: DirectoryDAO)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createUser(user: SamUser): Future[SamUser] = {
    directoryDAO.createUser(user)
  }

}
