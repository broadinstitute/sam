package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.sam.openam.OpenAmDAO
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceRole}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val openAmDAO: OpenAmDAO, val directoryDAO: JndiDirectoryDAO)(implicit val executionContext: ExecutionContext) {

  def createResourceType(resourceType: ResourceType): Future[Boolean] = {
    for {
      //Create the resource type if it doesn't exist
      uuid <- openAmDAO.createResourceType(resourceType)
      //Create the policy set
      result <- openAmDAO.createResourceTypePolicySet(uuid.get, resourceType)
    } yield result
  }

  def createResourceRole(resourceType: String, role: ResourceRole): Future[Boolean] = {
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
    //Query OpenAM to see if caller has permission to perform  action on resourceId
    //TODO

    Future.successful(StatusCodes.NoContent)
  }

}
