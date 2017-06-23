package org.broadinstitute.dsde.workbench.sam.openam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.{sprayJsValueUnmarshaller => _, _}
import akka.http.scaladsl.marshalling.{Marshal, Marshaller}
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryConfig, DirectorySubjectNameSupport}
import org.broadinstitute.dsde.workbench.sam.model.OpenAmJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class HttpOpenAmDAO(openAmConfig: OpenAmConfig, protected val directoryConfig: DirectoryConfig)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends OpenAmDAO with DirectorySubjectNameSupport {

  private def authorizationHeader(userInfo: UserInfo) = headers.RawHeader("iPlanetDirectoryPro", userInfo.accessToken)
  private val jsonContentTypeHeader = headers.`Content-Type`(ContentTypes.`application/json`)

  def getAdminUserInfo: Future[UserInfo] = getAuthToken(openAmConfig.user, openAmConfig.password).map(response => UserInfo(response.tokenId, SamUserId(openAmConfig.user)))

  def getAuthToken(username: String, password: String): Future[AuthenticateResponse] = {
    val authenticationUrl = openAmConfig.url + "/json/authenticate"
    val usernameHeader = headers.RawHeader("X-OpenAM-Username", username)
    val passwordHeader = headers.RawHeader("X-OpenAM-Password", password)

    httpRequest[String, AuthenticateResponse](authenticationUrl, Option("{}"), HttpMethods.POST, List(usernameHeader, passwordHeader))
  }

  def listResourceTypes(userInfo: UserInfo): Future[Set[OpenAmResourceType]] = {
    val resourceTypesUrl = openAmConfig.url + "/json/resourcetypes?_queryFilter=true"

    httpRequest[String, OpenAmResourceTypeList](resourceTypesUrl, None, HttpMethods.GET, List(authorizationHeader(userInfo))).map(_.result)
  }

  def createResourceType(resourceType: ResourceType, pattern: String, userInfo: UserInfo): Future[OpenAmResourceType] = {
    val action = "create"
    val resourceTypesUrl = openAmConfig.url + s"/json/resourcetypes/?_action=$action"
    val payload = OpenAmResourceTypePayload(resourceType.name, resourceType.actions.map(_ -> false).toMap, Set(pattern))

    httpRequest[OpenAmResourceTypePayload, OpenAmResourceType](resourceTypesUrl, Option(payload), HttpMethods.POST, List(authorizationHeader(userInfo)))
  }

  def updateResourceType(updatedResourceType: OpenAmResourceType, userInfo: UserInfo): Future[OpenAmResourceType] = {
    val resourceTypesUrl = openAmConfig.url + s"/json/resourcetypes/${updatedResourceType.uuid}"

    httpRequest[OpenAmResourceType, OpenAmResourceType](resourceTypesUrl, Option(updatedResourceType), HttpMethods.PUT, List(authorizationHeader(userInfo)))
  }

  def getDefaultPolicySet(userInfo: UserInfo): Future[OpenAmPolicySet] = {
    val policySetsUrl = openAmConfig.url + s"/json/applications/iPlanetAMWebAgentService"

    httpRequest[String, OpenAmPolicySet](policySetsUrl, None, HttpMethods.GET, List(authorizationHeader(userInfo)))
  }

  def updateDefaultPolicySet(updatedPolicySet: OpenAmPolicySet, userInfo: UserInfo): Future[OpenAmPolicySet] = {
    val policySetsUrl = openAmConfig.url + s"/json/applications/iPlanetAMWebAgentService"

    httpRequest[OpenAmPolicySet, OpenAmPolicySet](policySetsUrl, Option(updatedPolicySet), HttpMethods.PUT, List(authorizationHeader(userInfo)))
  }

  def createPolicy(name: String, description: String, actions: Seq[String], resources: Seq[String], subjects: Seq[SamSubject], resourceType: String, userInfo: UserInfo): Future[OpenAmPolicy] = {
    val action = "create"
    val policiesUrl = openAmConfig.url + s"/json/policies/?_action=$action"
    val openAmPolicySubject = OpenAmPolicySubject(subjects.map(subjectDn))
    val openAmPolicy = OpenAmPolicy(name, true, description, "iPlanetAMWebAgentService", actions.map(_ -> true).toMap, resources, openAmPolicySubject, resourceType)

    httpRequest[OpenAmPolicy, OpenAmPolicy](policiesUrl, Option(openAmPolicy), HttpMethods.POST, List(authorizationHeader(userInfo)))
  }

  def evaluatePolicy(urns: Set[String], userInfo: UserInfo): Future[List[OpenAmPolicyEvaluation]] = {
    val action = "evaluate"
    val policyUrl = openAmConfig.url + s"/json/policies?_action=$action"
    val urnSet = OpenAmResourceSet(urns)

    httpRequest[OpenAmResourceSet, List[OpenAmPolicyEvaluation]](policyUrl, Option(urnSet), HttpMethods.POST, List(authorizationHeader(userInfo)))
  }

  def httpRequest[A, B](uri: Uri, entity: Option[A], method: HttpMethod = HttpMethods.GET, headers: scala.collection.immutable.Seq[HttpHeader] = Nil)(implicit marshaller: Marshaller[A, RequestEntity], unmarshaller: Unmarshaller[ResponseEntity, B], errorReportSource: ErrorReportSource): Future[B] = {
    for {
      entity <- entity.map(e => Marshal(e).to[RequestEntity]).getOrElse(Future.successful(HttpEntity.Empty))
      response <- Http().singleRequest(HttpRequest(method = method, uri = uri, headers = headers, entity = entity.withContentType(ContentTypes.`application/json`)))
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
