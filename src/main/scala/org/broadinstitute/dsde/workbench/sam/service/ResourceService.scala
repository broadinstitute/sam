package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.sam.dataaccess.{AccessManagementDAO, DirectoryDAO}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessManagementDAO: AccessManagementDAO, val directoryDAO: DirectoryDAO)(implicit val executionContext: ExecutionContext) {

  def createResource(resourceType: String, resourceId: String): Future[(StatusCode, String)] = {
    for {
      x <- accessManagementDAO.createResource(resourceType, resourceId)
      //create policies
      //create groups
      //add caller to owner role
    } yield StatusCodes.Created -> x
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[(StatusCode, String)] = {
    accessManagementDAO.hasPermission(resourceType, resourceId, action) map { hasPermission =>
      if(hasPermission) StatusCodes.OK ->  s"$resourceType:$resourceId:$action"
      else StatusCodes.Forbidden ->  s"$resourceType:$resourceId:$action"
    }
  }

}
