package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, ExceptionHandler}
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.http.{
  HttpServerAttributesExtractor,
  HttpServerAttributesGetter,
  HttpServerMetrics,
  HttpServerRoute,
  HttpServerRouteSource,
  HttpSpanStatusExtractor
}
import io.opentelemetry.instrumentation.api.instrumenter.{Instrumenter, SpanKindExtractor}
import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.util
import java.util.regex.Pattern
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

/** Created by ajang on 2020-05-28
  */
trait SamRequestContextDirectives {
  val otel: OpenTelemetry

  /** Provides a new SamRequestContext with a root tracing span.
    */
  def withSamRequestContext: Directive1[SamRequestContext] =
    for {
      clientIP <- extractClientIP
      otelContext <- traceRequest
    } yield SamRequestContext(Option(otelContext), clientIP.toOption, openTelemetry = otel)

  /** Adds the route and parameters to telemetry. The route is consistent across all requests to the same endpoint. For example, if the route is
    * /api/resource/{resourceId}, then requests to /api/resource/123 and /api/resource/345 will have the same route. The route is constructed from the uri and
    * the parameters and values. It is not necessary to call this when there are no parameters in the URI. Only call this once when all parameters are known,
    * because of this, favor (get|put|post|delete|patch)WithTelemetry varietals.
    * @param samRequestContext
    * @param paramAndValues
    *   a sequence of tuples of parameter name and value for parameters that appear in the request uri. Parameter values in the URI are replaced with the
    *   parameter name enclosed with braces in order from left to right.
    * @return
    */
  def addTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    for {
      extractUri <- extractUri
      route = constructRoute(extractUri, paramAndValues)
    } yield samRequestContext.otelContext.foreach { otelContext =>
      paramAndValues.foreach { case (param, value) =>
        Span.fromContext(otelContext).setAttribute("param." + param, value.value)
      }

      // note that this is a no-op if the route is already set
      // even if the the route source is NESTED_CONTROLLER,
      // repeated calls will not update the route if the new route is shorter than the existing route
      HttpServerRoute.update(otelContext, HttpServerRouteSource.CONTROLLER, route)
    }

  def putWithTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    addTelemetry(samRequestContext, paramAndValues: _*) & put

  def getWithTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    addTelemetry(samRequestContext, paramAndValues: _*) & get

  def postWithTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    addTelemetry(samRequestContext, paramAndValues: _*) & post

  def deleteWithTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    addTelemetry(samRequestContext, paramAndValues: _*) & delete

  def patchWithTelemetry(samRequestContext: SamRequestContext, paramAndValues: (String, ValueObject)*): Directive0 =
    addTelemetry(samRequestContext, paramAndValues: _*) & patch

  /** Constructs a route from the uri and the parameters and values. Replace the values in the uri with the parameter name enclosed with braces. For example, if
    * the uri is /api/resource/123 and the parameters are ("resourceId", "123"), then the route will be /api/resource/{resourceId}.
    */
  private def constructRoute(uri: Uri, paramAndValues: Seq[(String, ValueObject)]): String = {
    val path = uri.path.toString()
    paramAndValues.foldLeft(path) { case (acc, (param, value)) =>
      acc.replaceFirst(Pattern.quote(s"/${value.value}"), s"/{$param}")
    }
  }

  private def traceRequest(): Directive1[Context] =
    extractRequest.flatMap { req =>
      val context = Context.current();
      if (instrumenter.shouldStart(context, req)) {
        val newContext = instrumenter.start(context, req)
        recordSuccess(instrumenter, newContext, req) &
          recordException(instrumenter, newContext, req) &
          provide(newContext)
      } else {
        provide(Context.current())
      }
    }

  private val instrumenter: Instrumenter[HttpRequest, HttpResponse] =
    Instrumenter
      .builder[HttpRequest, HttpResponse](otel, "SamRequest", req => req.uri.path.toString())
      .addAttributesExtractor(HttpServerAttributesExtractor.create[HttpRequest, HttpResponse](AkkaHttpServerAttributesGetter))
      .setSpanStatusExtractor(HttpSpanStatusExtractor.create(AkkaHttpServerAttributesGetter))
      .addOperationMetrics(HttpServerMetrics.get)
      .addContextCustomizer(HttpServerRoute.builder(AkkaHttpServerAttributesGetter).build())
      .buildInstrumenter(SpanKindExtractor.alwaysServer())

  private def recordSuccess(instrumenter: Instrumenter[HttpRequest, HttpResponse], context: Context, request: HttpRequest) =
    mapResponse { resp =>
      instrumenter.end(context, request, resp, null)
      resp
    }

  private def recordException(instrumenter: Instrumenter[HttpRequest, HttpResponse], context: Context, request: HttpRequest) =
    handleExceptions(ExceptionHandler { case NonFatal(ex) =>
      instrumenter.end(context, request, null, ex)
      throw ex
    })
}

object AkkaHttpServerAttributesGetter extends HttpServerAttributesGetter[HttpRequest, HttpResponse] {

  override def getUrlScheme(request: HttpRequest): String = request.uri.scheme

  override def getUrlPath(request: HttpRequest): String = request.uri.path.toString()

  override def getUrlQuery(request: HttpRequest): String = request.uri.query().toString()

  /** Defaults to the path of the request. Will be overridden by the `addRouteAndParamsToTrace` directive as necessary.
    */
  override def getHttpRoute(request: HttpRequest): String = getUrlPath(request)

  override def getHttpRequestMethod(request: HttpRequest): String = request.method.value

  override def getHttpRequestHeader(request: HttpRequest, name: String): util.List[String] = request.headers.filter(_.name == name).map(_.value).asJava

  override def getHttpResponseStatusCode(request: HttpRequest, response: HttpResponse, error: Throwable): Integer = response.status.intValue()

  override def getHttpResponseHeader(request: HttpRequest, response: HttpResponse, name: String): util.List[String] =
    response.headers.filter(_.name == name).map(_.value).asJava
}
