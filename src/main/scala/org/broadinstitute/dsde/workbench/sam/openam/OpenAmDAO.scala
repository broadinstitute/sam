package org.broadinstitute.dsde.workbench.sam.openam

import akka.actor.{ ActorSystem, Actor, ActorLogging }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpHeader, HttpMethods, HttpRequest, HttpResponse}
import akka.stream.Materializer
import org.broadinstitute.dsde.workbench.sam.model.Resource

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class OpenAmDAO(serverUrl: String)(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) {

  def createResourceType(resource: Resource): Future[Boolean] = {
    val url = serverUrl + "/json/resourcetypes"

    val responseFuture: Future[HttpResponse] =
      Http().singleRequest(HttpRequest(uri = "https://openam101.dsde-dev.broadinstitute.org/openam/"))

    responseFuture.map { response =>
      println("start")
      println(response)
      println("finish")
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
