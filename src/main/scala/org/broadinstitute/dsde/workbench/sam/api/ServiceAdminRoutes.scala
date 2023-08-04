package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes.{NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._

trait ServiceAdminRoutes extends SecurityDirectives with SamRequestContextDirectives with SamUserDirectives with SamModelDirectives {

  val resourceService: ResourceService

  def serviceAdminRoutes(requestContext: SamRequestContext): server.Route =
    pathPrefix("admin") {
      pathPrefix("v2") {
        asAdminServiceUser {
          serviceAdminUserRoutes(requestContext)
        }
      }
    }

  def serviceAdminUserRoutes(samRequestContext: SamRequestContext): server.Route =
    pathPrefix("users") {
      get {
        parameters("id".optional, "googleSubjectId".optional, "azureB2CId".optional) { (id, googleSubjectId, azureB2CId) =>
          complete {
            userService
              .getUsersByQuery(id.map(WorkbenchUserId), googleSubjectId.map(GoogleSubjectId), azureB2CId.map(AzureB2CId), samRequestContext)
              .map(users => (if (users.isEmpty) OK else NotFound) -> users)
          }
        }
      }

    }
}
