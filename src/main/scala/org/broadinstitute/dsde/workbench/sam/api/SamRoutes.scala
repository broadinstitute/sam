package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, RouteResult}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.LazyLogging
import io.sentry.Sentry
import net.logstash.logback.argument.StructuredArguments
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.oauth2.OpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.azure.{AzureRoutes, AzureService}
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.AdminConfig
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, TermsOfServiceConfig}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service._
import scala.jdk.CollectionConverters._

import scala.concurrent.{ExecutionContext, Future}

/** Created by dvoet on 5/17/17.
  */
abstract class SamRoutes(
    val resourceService: ResourceService,
    val userService: UserService,
    val statusService: StatusService,
    val managedGroupService: ManagedGroupService,
    val termsOfServiceConfig: TermsOfServiceConfig,
    val policyEvaluatorService: PolicyEvaluatorService,
    val tosService: TosService,
    val liquibaseConfig: LiquibaseConfig,
    val oidcConfig: OpenIDConnectConfiguration,
    val adminConfig: AdminConfig,
    val azureService: Option[AzureService]
)(implicit
    val system: ActorSystem,
    val materializer: Materializer,
    val executionContext: ExecutionContext
) extends LazyLogging
    with ResourceRoutes
    with OldUserRoutes
    with StatusRoutes
    with TermsOfServiceRoutes
    with ExtensionRoutes
    with ManagedGroupRoutes
    with AdminRoutes
    with AzureRoutes
    with ServiceAdminRoutes
    with UserRoutesV1
    with UserRoutesV2 {

  def route: server.Route = (logRequestResult & handleExceptions(myExceptionHandler)) {
    oidcConfig.swaggerRoutes("swagger/api-docs.yaml") ~
    oidcConfig.oauth2Routes ~
    statusRoutes ~
    oldTermsOfServiceRoutes ~
    publicTermsOfServiceRoutes ~
    withExecutionContext(ExecutionContext.global) {
      withSamRequestContext { samRequestContext =>
        pathPrefix("register")(oldUserRoutes(samRequestContext)) ~
        pathPrefix("api") {
          // these routes are for machine to machine authorized requests
          // the whitelisted service admin account email is in the header of the request
          serviceAdminRoutes(samRequestContext) ~
          userRoutesV2(samRequestContext) ~
          userTermsOfServiceRoutes(samRequestContext) ~
          withActiveUser(samRequestContext) { samUser =>
            val samRequestContextWithUser = samRequestContext.copy(samUser = Option(samUser))
            logRequestResultWithSamUser(samUser) {
              resourceRoutes(samUser, samRequestContextWithUser) ~
              adminRoutes(samUser, samRequestContextWithUser) ~
              extensionRoutes(samUser, samRequestContextWithUser) ~
              groupRoutes(samUser, samRequestContextWithUser) ~
              azureRoutes(samUser, samRequestContextWithUser) ~
              userRoutesV1(samUser, samRequestContextWithUser)
            }
          }
        }
      }
    }
  }

  // basis for logRequestResult lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private def logRequestResult: Directive0 = {
    def entityAsString(entity: HttpEntity): Future[String] =
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case 4 => Logging.InfoLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity).map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.DebugLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))
  }

  private def logRequestResultWithSamUser(samUser: SamUser): Directive0 = {

    def logSamUserRequest(unusedLogger: LoggingAdapter)(req: HttpRequest)(res: RouteResult): Unit =
      res match {
        case Complete(resp) =>
          logger.info(
            s"Request from user ${samUser.id} (${samUser.email})",
            StructuredArguments.keyValue(
              "eventMetrics",
              Map("request" -> req.uri, "metricsLog" -> true, "event" -> "sam:api-request:complete", "status" -> resp.status.intValue.toString).asJava
            )
          )
        case _ =>
          logger.warn(
            s"Request from user ${samUser.id} (${samUser.email})",
            StructuredArguments.keyValue("eventMetrics", Map("request" -> req.uri, "metricsLog" -> true, "event" -> "sam:api-request:incomplete").asJava)
          )
      }
    DebuggingDirectives.logRequestResult(LoggingMagnet(log => logSamUserRequest(log)))
  }
}

object SamRoutes {
  protected[sam] val myExceptionHandler = {
    import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

    ExceptionHandler {
      case withErrorReport: WorkbenchExceptionWithErrorReport =>
        Sentry.captureException(withErrorReport)
        complete((withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), withErrorReport.errorReport))
      case e: Throwable =>
        Sentry.captureException(e)
        complete((StatusCodes.InternalServerError, ErrorReport(e)))
    }
  }
}
