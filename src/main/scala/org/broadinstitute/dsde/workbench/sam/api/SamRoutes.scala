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
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.oauth2.OpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes._
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, TermsOfServiceConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.service._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 5/17/17.
  */
abstract class SamRoutes(
    val resourceService: ResourceService,
    val userService: UserService,
    val statusService: StatusService,
    val managedGroupService: ManagedGroupService,
    val termsOfServiceConfig: TermsOfServiceConfig,
    val directoryDAO: DirectoryDAO,
    val registrationDAO: RegistrationDAO,
    val policyEvaluatorService: PolicyEvaluatorService,
    val tosService: TosService,
    val liquibaseConfig: LiquibaseConfig,
    val oidcConfig: OpenIDConnectConfiguration)(
    implicit val system: ActorSystem,
    val materializer: Materializer,
    val executionContext: ExecutionContext)
    extends LazyLogging
    with ResourceRoutes
    with UserRoutes
    with StatusRoutes
    with TermsOfServiceRoutes
    with ExtensionRoutes
    with ManagedGroupRoutes
    with AdminRoutes {

  def route: server.Route = (logRequestResult & handleExceptions(myExceptionHandler)) {
    oidcConfig.swaggerRoutes("swagger/api-docs.yaml") ~
      oidcConfig.oauth2Routes ~
      statusRoutes ~
      termsOfServiceRoutes ~
      withExecutionContext(ExecutionContext.global) {
        withSamRequestContext { samRequestContext =>
          pathPrefix("register") { userRoutes(samRequestContext) } ~
          pathPrefix("api") {
            // IMPORTANT - all routes under /api must have an active user
            withActiveUser(samRequestContext) { samUser =>
              val samRequestContextWithUser = samRequestContext.copy(samUser = Option(samUser))
              resourceRoutes(samUser, samRequestContextWithUser) ~
                adminRoutes(samUser, samRequestContextWithUser) ~
                adminUserRoutes(samUser, samRequestContextWithUser) ~
                extensionRoutes(samUser, samRequestContextWithUser) ~
                groupRoutes(samUser, samRequestContextWithUser) ~
                apiUserRoutes(samUser, samRequestContextWithUser)
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

  def statusCodeCreated[T](response: T): (StatusCode, T) = (StatusCodes.Created, response)

}

object SamRoutes {
  protected[sam] val myExceptionHandler = {
    import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

    ExceptionHandler {
      case withErrorReport: WorkbenchExceptionWithErrorReport =>
        complete((withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), withErrorReport.errorReport))
      case e: Throwable =>
        complete((StatusCodes.InternalServerError, ErrorReport(e)))
    }
  }
}
