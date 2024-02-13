package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, ExceptionHandler}
import bio.terra.common.opentelemetry.HttpServerMetrics
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.semconv.http._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.util
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Created by ajang on 2020-05-28
  */
trait SamRequestContextDirectives {
  // lazy to make sure GlobalOpenTelemetry is initialized
  private lazy val instrumenter: Instrumenter[HttpRequest, HttpResponse] =
    Instrumenter
      .builder[HttpRequest, HttpResponse](GlobalOpenTelemetry.get(), "SamRequest", HttpSpanNameExtractor.create(AkkaHttpServerAttributesGetter))
      .addAttributesExtractor(HttpServerAttributesExtractor.create[HttpRequest, HttpResponse](AkkaHttpServerAttributesGetter))
      .setSpanStatusExtractor(HttpSpanStatusExtractor.create(AkkaHttpServerAttributesGetter))
      .addOperationMetrics(HttpServerMetrics.get)
      .addContextCustomizer(HttpServerRoute.builder(AkkaHttpServerAttributesGetter).build())
      .buildServerInstrumenter(new TextMapGetter[HttpRequest] {
        override def get(carrier: HttpRequest, key: String): String = carrier.headers.find(_.name == key).map(_.value).orNull
        override def keys(carrier: HttpRequest): java.lang.Iterable[String] = carrier.headers.map(_.name).asJava
      })

  /** Provides a new SamRequestContext with a root tracing span.
    */
  def withSamRequestContext: Directive1[SamRequestContext] =
    for {
      clientIP <- extractClientIP
      otelContext <- traceRequest
    } yield SamRequestContext(Option(otelContext), clientIP.toOption)

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

  /** Defaults to the path of the request. Overridden by the `addTelemetry` directive.
    */
  override def getHttpRoute(request: HttpRequest): String =
    SwaggerRouteMatcher.matchRoute(getUrlPath(request)).map(_.route).getOrElse(getUrlPath(request))

  override def getHttpRequestMethod(request: HttpRequest): String = request.method.value

  override def getHttpRequestHeader(request: HttpRequest, name: String): util.List[String] = request.headers.filter(_.name == name).map(_.value).asJava

  override def getHttpResponseStatusCode(request: HttpRequest, response: HttpResponse, error: Throwable): Integer = response.status.intValue()

  override def getHttpResponseHeader(request: HttpRequest, response: HttpResponse, name: String): util.List[String] =
    response.headers.filter(_.name == name).map(_.value).asJava
}
