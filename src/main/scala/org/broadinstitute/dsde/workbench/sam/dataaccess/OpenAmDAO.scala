package org.broadinstitute.dsde.workbench.sam.dataaccess

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, HttpRequest, HttpResponse}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import org.broadinstitute.dsde.workbench.sam.model.SamModels._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(serverUrl: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) {

  def createResourceType(resource: Resource): Future[Boolean] = {
    val url = serverUrl + "/json/resourcetypes?_queryFilter=true"

    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "https://openam101.dsde-dev.broadinstitute.org/openam/"))


    val thing: Flow[HttpRequest, HttpResponse, Any] = Http().outgoingConnection(host = url)

    thing.flatMap { z =>
      println(z)
      true
    }
  }

  def createResource(resourceType: String, resourceId: String): Future[Boolean] = {
    Future.successful(true)
  }

  def hasPermission(resourceType: String, resourceId: String, action: String): Future[Boolean] = {
    Future.successful(true)
  }

  def createPolicy(): Future[Boolean] = {
    Future.successful(true)
  }

  def addUserToRole(): Future[Boolean] = {
    Future.successful(true)
  }

}
