package org.broadinstitute.dsde.workbench.sam.dataaccess

import scala.concurrent.Future

/**
  * Created by mbemis on 5/22/17.
  */
class AccessManagementDAO(serverUrl: String) {

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createPolicy(): Future[Boolean] = {
    Future.successful(true)
  }

  def createGroup(): Future[Boolean] = {
    Future.successful(true)
  }

  def addUserToRole(): Future[Boolean] = {
    Future.successful(true)
  }

  def listRoleGroups(): Future[Boolean] = {
    Future.successful(true)
  }

}
