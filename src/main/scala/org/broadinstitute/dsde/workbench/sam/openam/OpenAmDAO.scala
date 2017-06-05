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
  implicit val openAmResourceTypeFormat = jsonFormat3(OpenAmResourceType)
  implicit val openAmPolicySetFormat = jsonFormat6(OpenAmPolicySet)
}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(serverUrl: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends JsonSupport {

  val authorizationHeader = headers.RawHeader("iPlanetDirectoryPro", "...")
  val jsonHeader = headers.`Content-Type`(ContentTypes.`application/json`)

//  def getAdminToken(): String = {
//    val authenticationUrl = serverUrl + s"/json/authenticate"
//    val usernameHeader = headers.RawHeader("X-OpenAM-Username", "removed")
//    val passwordHeader = headers.RawHeader("X-OpenAM-Password", "removed")
//
//    val request = for {
//      requestEntity <- Marshal("{}").to[RequestEntity]
//      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = authenticationUrl, headers = List(usernameHeader, passwordHeader, jsonHeader), entity = requestEntity))
//      responseEntity <- Unmarshal(response.entity).to[String]
//    } yield responseEntity
//
//    Await.result(request.map { response =>
//      println("Response: " + response)
//      response
//      //response("tokenId")
//    }, Duration.Inf)
//  }

  def listResourceTypes(): Future[Boolean] = {
    val resourceTypesUrl = serverUrl + "/json/resourcetypes?_queryFilter=true"

    val request = for {
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = resourceTypesUrl, headers = List(authorizationHeader)))
      responseEntity <- Unmarshal(response.entity).to[String]
    } yield responseEntity

    request.map { response =>
      println("Response: " + response)
      true
    }
  }

  def createResourceType(resource: ResourceType): Future[Option[String]] = {
    val action = "create"
    val resourceTypesUrl = serverUrl + s"/json/resourcetypes/?_action=$action"
    val payload = resource.asOpenAm

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = resourceTypesUrl, headers = List(authorizationHeader), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject] //TODO: define an object to unmarshal this to
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      if(statusCode.isSuccess) {
        println(response)
        Option(response.fields("uuid").convertTo[String])
      } else None
    }
  }

  def createResource(resourceType: String, resourceId: String): Future[Boolean] = {
    Future.successful(true)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createResourceTypePolicySet(uuid: String, resourceType: ResourceType): Future[Boolean] = {
    val action = "create"
    val policiesUrl = serverUrl + s"json/applications/?_action=$action"
    val policyName = s"$resourceType-policies"

    println(policyName)
    println(policiesUrl)

    val payload = PolicySet(policyName, Set.empty, Set(uuid)).asOpenAm

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = policiesUrl, headers = List(authorizationHeader), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject]
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      println(response)
      statusCode.isSuccess()
    }
  }

}
