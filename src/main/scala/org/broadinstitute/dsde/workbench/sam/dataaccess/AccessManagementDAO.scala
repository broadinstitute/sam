package org.broadinstitute.dsde.workbench.sam.dataaccess

import scala.concurrent.Future

/**
  * Created by mbemis on 5/22/17.
  */
class AccessManagementDAO(serverUrl: String) {

  def createResource(resourceType: String, resourceId: String): Future[Boolean] = {
    Future.successful(true)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createPolicy(): Future[Boolean] = {
    Future.successful(true)
  }

  def addUserToRole(): Future[Boolean] = {
    Future.successful(true)
  }

  def listRoleGroups(): Future[Boolean] = {
    Future.successful(true)
  }

}
