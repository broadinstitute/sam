package org.broadinstitute.dsde.workbench.sam.openam

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.util.ByteString
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceRole}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(serverUrl: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) {

  def listResourceTypes(): Future[Boolean] = {
    val resourceTypesUrl = serverUrl + "/json/resourceTypes?_queryFilter=true"

    Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = resourceTypesUrl))

    Future.successful(true)
  }

  def createResourceType(resource: ResourceType): Future[Boolean] = {
    val action = "create"
    val resourceTypesUrl = serverUrl + s"/json/resourcetypes/?_action=$action"
    val payload = resource.asOpenAm

    Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = resourceTypesUrl))

    Future.successful(true)
  }

  def createResource(resourceType: String, resourceId: String): Future[Boolean] = {
    Future.successful(true)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createResourcePolicy(resourceType: String, resourceRole: ResourceRole): Future[Boolean] = {
    val policiesUrl = serverUrl + "/json/policies"
    val policyName = s"$resourceType-${resourceRole.roleName}"
    val policyActions = resourceRole.actions

    Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = policiesUrl))

    Future.successful(true)
  }

}
