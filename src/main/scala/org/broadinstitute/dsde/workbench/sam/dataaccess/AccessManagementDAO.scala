package org.broadinstitute.dsde.workbench.sam.dataaccess

import scala.concurrent.Future

/**
  * Created by mbemis on 5/22/17.
  */
class AccessManagementDAO(serverUrl: String) {

  def createResource(resourceType: String, resourceId: String): Future[String] = {
    Future.successful(s"Resource Created: $resourceType/$resourceId")
  }

  def createPolicy() = ???
  def createGroup() = ???
  def addUserToRole() = ???
  def getRoleGroups() = ???
  def hasPermission(resourceType: String, resourceId: String, action: String) = ???

}
