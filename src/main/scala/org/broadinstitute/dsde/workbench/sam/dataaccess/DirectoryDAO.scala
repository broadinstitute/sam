package org.broadinstitute.dsde.workbench.sam.dataaccess

import scala.concurrent.Future

/**
  * Created by mbemis on 5/23/17.
  */
class DirectoryDAO(serverUrl: String) {

  def createResource(resourceType: String, resourceId: String): Future[String] = {
    Future.successful(s"$resourceType:$resourceId")
  }

}
