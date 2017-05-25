package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.sam.dataaccess.{AccessManagementDAO, DirectoryDAO}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessManagementDAO: AccessManagementDAO, val directoryDAO: DirectoryDAO)(implicit val executionContext: ExecutionContext) {

  def createResource(resourceType: String, resourceId: String): Future[StatusCode] = {
    for {
      resource <- directoryDAO.createResource(resourceType, resourceId) // Create resource
      policies <- accessManagementDAO.createPolicy()                    // Create policies
      groups   <- accessManagementDAO.createGroup()                     // Create groups
                                                                        // Add caller to OWNER role
    } yield StatusCodes.NoContent
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[StatusCode] = {
    accessManagementDAO.hasPermission(resourceType, resourceId, action) map { hasPermission =>
      StatusCodes.NoContent
    }
  }

}
