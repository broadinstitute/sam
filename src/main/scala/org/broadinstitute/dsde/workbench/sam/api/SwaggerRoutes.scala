package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import org.broadinstitute.dsde.workbench.sam.config.SwaggerConfig

/**
  * Created by dvoet on 7/18/17.
  */
trait SwaggerRoutes {
  private val swaggerUiPath = "META-INF/resources/webjars/swagger-ui/3.49.0"

  val swaggerConfig: SwaggerConfig

  val swaggerRoutes: server.Route = {
    path("") {
      get {
          serveIndex
      }
    } ~
      path("api-docs.yaml") {
        get {
          getFromResource("swagger/api-docs.yaml")
        }
      } ~
      path("swagger-ui-bundle.js") {
        get {
          serveSwaggerUiBundle
        }
       } ~
      // We have to be explicit about the paths here since we're matching at the root URL and we don't
      // want to catch all paths lest we circumvent Spray's not-found and method-not-allowed error
      // messages.
      (pathPrefixTest("swagger-ui") | pathPrefixTest("oauth2") | pathSuffixTest("js")
        | pathSuffixTest("css") | pathPrefixTest("favicon")) {
        get {
          getFromResourceDirectory(swaggerUiPath)
        }
      }
  }

  private val serveIndex: server.Route = {
    val swaggerOptions =
      """
        |        validatorUrl: null,
        |        apisSorter: "alpha",
        |        operationsSorter: "alpha"
      """.stripMargin

    mapResponseEntity { entityFromJar =>
      entityFromJar.transformDataBytes(Flow.fromFunction[ByteString, ByteString] { original: ByteString =>
        ByteString(
          original.utf8String
            .replace("""url: "https://petstore.swagger.io/v2/swagger.json"""", "url: '/api-docs.yaml'")
            .replace("""layout: "StandaloneLayout"""", s"""layout: "StandaloneLayout", $swaggerOptions""")
            .replace("window.ui = ui", s"""ui.initOAuth({
                                          |        clientId: "${swaggerConfig.googleClientId}",
                                          |        clientSecret: "${swaggerConfig.realm}",
                                          |        realm: "${swaggerConfig.realm}",
                                          |        appName: "${swaggerConfig.realm}",
                                          |        scopeSeparator: " ",
                                          |        additionalQueryStringParams: {}
                                          |      })
                                          |      window.ui = ui
                                          |      """.stripMargin)
        )
      })
    } {
      getFromResource(s"$swaggerUiPath/index.html")
    }
  }
    private val serveSwaggerUiBundle: server.Route = {
    mapResponseEntity { entityFromJar =>
      entityFromJar.transformDataBytes(Flow.fromFunction[ByteString, ByteString] { original: ByteString =>
        ByteString(
          original.utf8String
            .replace("response_type=token", "response_type=id_token token")
        )
      })
    } {
      getFromResource(s"$swaggerUiPath/swagger-ui-bundle.js")
    }
  }
}
