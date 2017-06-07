package org.broadinstitute.dsde.workbench.sam.openam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.sam.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

// TODO: Move out
trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val resourceTypeFormat = jsonFormat4(ResourceType)
  implicit val openAmResourceTypeFormat = jsonFormat6(OpenAmResourceType)
  implicit val openAmResourceTypesResponseFormat = jsonFormat1(OpenAmResourceTypesResponse)
  implicit val openAmPolicySetFormat = jsonFormat6(OpenAmPolicySet)
}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(serverUrl: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends JsonSupport {

  val authorizationHeader = headers.RawHeader("iPlanetDirectoryPro", "AQIC5wM2LY4SfcxH25hHd87ov8f0sf2nvX5ujL1mUdNEHT0.*AAJTSQACMDEAAlNLABQtNzMxNzYwMjQxMjMxOTU3MzY2MQACUzEAAA..*")
  val jsonHeader = headers.`Content-Type`(ContentTypes.`application/json`)

  def listResourceTypes(): Future[Set[OpenAmResourceType]] = {
    val resourceTypesUrl = serverUrl + "/json/resourcetypes?_queryFilter=true"

    for {
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = resourceTypesUrl, headers = List(authorizationHeader)))
      responseEntity <- Unmarshal(response.entity).to[OpenAmResourceTypesResponse]
    } yield responseEntity.result
  }

  def createResourceType(resource: ResourceType): Future[Option[OpenAmResourceType]] = {
    val action = "create"
    val resourceTypesUrl = serverUrl + s"/json/resourcetypes/?_action=$action"
    val payload = resource

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = resourceTypesUrl, headers = List(authorizationHeader), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[OpenAmResourceType] //TODO: define an object to unmarshal this to
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      if(statusCode.isSuccess) {
        Option(response)
      } else None
    }
  }

  def updateResourceType(resourceType: OpenAmResourceType): Future[Boolean] = {
    val resourceTypesUrl = serverUrl + s"/json/resourcetypes/${resourceType.uuid}"
    val payload = resourceType.asPayload

    println(resourceType + "xxxxxx")

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.PUT, uri = resourceTypesUrl, headers = List(authorizationHeader), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject] //TODO: unmarshal this to a real object
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      println(s"Updated resource type: $response")
      statusCode.isSuccess
    }
  }

  def createResourceTypePolicySet(resourceType: OpenAmResourceType): Future[Boolean] = {
    val action = "create"
    val policiesUrl = serverUrl + s"json/applications/?_action=$action"
    val policyName = s"$resourceType-policies"
    val payload = PolicySet(policyName, Set.empty, Set(resourceType.uuid)).asOpenAm

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = policiesUrl, headers = List(authorizationHeader), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject]
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      println(s"Created policy set: $response")
      statusCode.isSuccess()
    }
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

}
