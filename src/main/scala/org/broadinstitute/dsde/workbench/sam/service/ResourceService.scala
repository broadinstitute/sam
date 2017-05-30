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
    //TODO

    //Create the roles and set their actions for the resource type
    Future.traverse(resource.roles) { createResourceRole(resource.resourceType, _) }

    Future.successful(true)
  }

  def createResourceRole(resourceType: String, role: ResourceRole): Future[Boolean] = {
    println(s"Creating resource role: $role")

    //Set the actions for the role
    //TODO

    Future.successful(true)
  }

  def createResource(resourceType: String, resourceId: String): Future[StatusCode] = {
    //Ensure resource type exists
    //TODO

    //Create resource
    //TODO

    //Create policies
    //TODO

    //Create groups
    //TODO

    //Add caller to Owner role
    //TODO

    Future.successful(StatusCodes.NoContent)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[StatusCode] = {
    //Query OpenAM to see if call has permission to perform  action on resourceId
    //TODO

    Future.successful(StatusCodes.NoContent)
  }

}
