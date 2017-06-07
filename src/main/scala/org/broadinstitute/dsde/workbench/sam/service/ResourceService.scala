package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import org.broadinstitute.dsde.workbench.sam.openam.OpenAmDAO
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{OpenAmResourceType, ResourceRole, ResourceType}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val openAmDAO: OpenAmDAO, val directoryDAO: JndiDirectoryDAO)(implicit val executionContext: ExecutionContext) {

  def syncResourceTypes(resourceTypes: Set[ResourceType]): Future[Boolean] = {
    openAmDAO.listResourceTypes().flatMap { existingTypesResponse =>
      // Pull out new types, create them
      // Pull out changed types, update them
      // Ignore all other types
      val newTypes = resourceTypes.filter(t => existingTypesResponse.map(_.name).contains(t.name)) //TODO
      val existingTypesAsSam = existingTypesResponse.map(x => ResourceType(x.name, x.description, x.patterns, x.actions))
      val updatedTypes = existingTypesResponse.filter(x => resourceTypes.contains(x.asPayload))

      println(newTypes)
      println(updatedTypes)

      for {
        createResults <- Future.traverse(newTypes)(createResourceType)
        updateResults <- Future.traverse(updatedTypes)(t => openAmDAO.updateResourceType(t))
      } yield (updateResults ++ createResults).forall(_ == true)
    }
  }

  def listResourceTypes(): Future[Set[OpenAmResourceType]] = {
    openAmDAO.listResourceTypes()
  }

  def createResourceType(resourceType: ResourceType): Future[Boolean] = {
    for {
      //Create the resource type if it doesn't exist
      openAmResourceType <- openAmDAO.createResourceType(resourceType)
      //Create the policy set
      result <- if(openAmResourceType.isDefined) openAmDAO.createResourceTypePolicySet(openAmResourceType.get) else Future.successful(false)
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
