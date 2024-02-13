package org.broadinstitute.dsde.workbench.sam.api

import io.swagger.v3.parser.OpenAPIV3Parser

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

final private case class SwaggerRouteInfo(path: String, regex: Regex, parameterNames: List[String])
final case class MatchedRoute(route: String, parametersByName: Map[String, String])

/** Utility for matching a path to a Swagger route and extracting parameters. Routes contain parameter names in curly braces, e.g.
  * /api/workspaces/{workspaceNamespace}/{workspaceName}. Paths are the routes with the parameters filled in e.g.
  * /api/workspaces/a_real_namespace/a_real_workspace_name. This class uses the Swagger API documentation to match paths to routes and extract parameters.
  */
object SwaggerRouteMatcher {

  private def loadSwaggerPaths(): List[String] = {
    val swaggerResource = scala.io.Source.fromResource("swagger/api-docs.yaml")
    val parseResults = new OpenAPIV3Parser().readContents(swaggerResource.mkString)
    parseResults.getOpenAPI.getPaths.keySet().asScala.toList
  }

  private val parseRoutes: List[SwaggerRouteInfo] =
    loadSwaggerPaths()
      .map { path =>
        val parameterNames = "\\{([^}]+)}".r.findAllMatchIn(path).map(_.group(1)).toList
        // create a regex that matches the path, replacing the parameter names with a named regex that matches anything
        val regex = parameterNames
          .foldLeft(path) { (acc, param) =>
            acc.replace(s"{$param}", s"(?<$param>[^/]+)")
          }
          .r
        SwaggerRouteInfo(path, regex, parameterNames)
      // sort the routes by the number of parameters, so that the most specific routes are first
      }
      .sortBy(_.parameterNames.length)

  /** Match a path to a Swagger route and extract parameters.
    * @param path
    *   the path to match
    * @return
    *   the matched route and extracted parameters, if any
    */
  def matchRoute(path: String): Option[MatchedRoute] =
    // find the first route that matches the path
    // LazyList is used to avoid evaluating the entire list of routes
    parseRoutes.to(LazyList).map(route => (route, route.regex.pattern.matcher(path))).collectFirst {
      case (route, matcher) if matcher.matches() =>
        val parameters = route.parameterNames.map { name =>
          name -> matcher.group(name)
        }.toMap
        MatchedRoute(route.path, parameters)
    }
}
