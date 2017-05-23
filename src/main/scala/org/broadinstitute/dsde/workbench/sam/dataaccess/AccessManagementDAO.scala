package org.broadinstitute.dsde.workbench.sam.dataaccess

import scala.concurrent.Future

/**
  * Created by mbemis on 5/22/17.
  */
class AccessManagementDAO(serverUrl: String) {

  def createResource(resourceType: String, resourceId: String): Future[String] = {
    Future.successful(s"$resourceType:$resourceId")
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createPolicy() = ???
  def createGroup() = ???
  def addUserToRole() = ???
  def listRoleGroups() = ???

}
