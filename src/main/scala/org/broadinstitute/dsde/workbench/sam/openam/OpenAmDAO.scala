package org.broadinstitute.dsde.workbench.sam.openam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.sam.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryConfig, DirectorySubjectNameSupport}
import spray.json._
import SprayJsonSupport._
import DefaultJsonProtocol._
import akka.http.scaladsl.marshalling.Marshaller
import akka.http.scaladsl.unmarshalling.Unmarshaller
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.openam.OpenAmJsonSupport._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(openAmConfig: OpenAmConfig, protected val directoryConfig: DirectoryConfig)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends DirectorySubjectNameSupport {

  private def authorizationHeader(userInfo: UserInfo) = headers.RawHeader("iPlanetDirectoryPro", userInfo.accessToken)
  private val jsonContentTypeHeader = headers.`Content-Type`(ContentTypes.`application/json`)

  def getAdminAuthToken(): Future[String] = getAuthToken(openAmConfig.user, openAmConfig.password).map(_.tokenId)

  def getAuthToken(username: String, password: String): Future[AuthenticateResponse] = {
    val authenticationUrl = openAmConfig.url + s"/json/authenticate"
    val usernameHeader = headers.RawHeader("X-OpenAM-Username", username)
    val passwordHeader = headers.RawHeader("X-OpenAM-Password", password)

    httpRequest[String, AuthenticateResponse](authenticationUrl, "{}", HttpMethods.POST, List(usernameHeader, passwordHeader, jsonContentTypeHeader))
  }

  def listResourceTypes(userInfo: UserInfo): Future[Boolean] = {
    val resourceTypesUrl = openAmConfig.url + "/json/resourcetypes?_queryFilter=true"

    val request = for {
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = resourceTypesUrl, headers = List(authorizationHeader(userInfo))))
      responseEntity <- Unmarshal(response.entity).to[String]
    } yield responseEntity

    request.map { response =>
      println("Response: " + response)
      true
    }
  }

  def createResourceType(resource: ResourceType, userInfo: UserInfo): Future[Option[String]] = {
    val action = "create"
    val resourceTypesUrl = openAmConfig.url + s"/json/resourcetypes/?_action=$action"
    val payload = resource.asOpenAm

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = resourceTypesUrl, headers = List(authorizationHeader(userInfo)), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject] //TODO: define an object to unmarshal this to
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      if(statusCode.isSuccess) {
        println(response)
        Option(response.fields("uuid").convertTo[String])
      } else None
    }
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createResourceTypePolicySet(uuid: String, resourceType: ResourceType, userInfo: UserInfo): Future[Boolean] = {
    val action = "create"
    val policiesUrl = openAmConfig.url + s"json/applications/?_action=$action"
    val policyName = s"$resourceType-policies"

    println(policyName)
    println(policiesUrl)

    val payload = PolicySet(policyName, Set.empty, Set(uuid)).asOpenAm

    val request = for {
      requestEntity <- Marshal(payload).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = policiesUrl, headers = List(authorizationHeader(userInfo)), entity = requestEntity))
      responseEntity <- Unmarshal(response.entity).to[JsObject]
    } yield (response.status, responseEntity)

    request.map { case (statusCode, response) =>
      println(response)
      statusCode.isSuccess()
    }
  }

  def createPolicy(name: String, description: String, actions: Seq[String], resources: Seq[String], subjects: Seq[SamSubject], resourceType: String, userInfo: UserInfo): Future[OpenAmPolicy] = {
    val action = "create"
    val policiesUrl = openAmConfig.url + s"json/policies/?_action=$action"

    val openAmPolicySubject = OpenAmPolicySubject(subjects.map(subjectDn))
    val openAmPolicy = OpenAmPolicy(name, true, description, "iPlanetAMWebAgentService", actions.map(_ -> true).toMap, resources, openAmPolicySubject, resourceType)

    httpRequest[OpenAmPolicy, OpenAmPolicy](policiesUrl, openAmPolicy, HttpMethods.POST, List(authorizationHeader(userInfo), jsonContentTypeHeader))
  }

  def httpRequest[A, B](uri: Uri, entity: A, method: HttpMethod = HttpMethods.GET, headers: scala.collection.immutable.Seq[HttpHeader] = Nil)(implicit marshaller: Marshaller[A, RequestEntity], unmarshaller: Unmarshaller[ResponseEntity, B], errorReportSource: ErrorReportSource): Future[B] = {
    for {
      requestEntity <- Marshal(entity).to[RequestEntity]
      response <- Http().singleRequest(HttpRequest(method = method, uri = uri, headers = headers, entity = requestEntity))
      responseEntity <- unmarshalResponseOrError(response)
    } yield responseEntity
  }

  private def unmarshalResponseOrError[B, A](response: HttpResponse)(implicit unmarshaller: Unmarshaller[ResponseEntity, B], errorReportSource: ErrorReportSource) = {
    if (response.status.isSuccess()) {
      Unmarshal(response.entity).to[B]
    } else {
      Unmarshal(response.entity).to[String] flatMap { message =>
        Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(response.status, message)))
      }
    }
  }
}
