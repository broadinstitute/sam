package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.sam.dataaccess.{AccessManagementDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamModels._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessManagementDAO: AccessManagementDAO, val directoryDAO: DirectoryDAO)(implicit val executionContext: ExecutionContext) {

  def createResourceType(resource: Resource): Future[Boolean] = {
    println(s"Creating resource: $resource")
    //Create the resource type if it doesn't exist
    //Create the roles and actions for that resource type

    Future.successful(true)
  }

  def createResource(resourceType: String, resourceId: String): Future[StatusCode] = {
    //Ensure resource type exists
    //Create resource
    //Create policies
    //Create groups
    //Add caller to Owner role

    Future.successful(StatusCodes.NoContent)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[StatusCode] = {
    //Query OpenAM to see if call has permission to perform  action on resourceId

    Future.successful(StatusCodes.NoContent)
  }

}
