package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.event.Logging.LogLevel
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LogEntry, LoggingMagnet}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.model.ErrorReport

import SprayJsonSupport._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by dvoet on 5/17/17.
  */
class SamRoutes(implicit val system: ActorSystem, val materializer: Materializer, val executionContext: ExecutionContext) extends LazyLogging {
  private val myExceptionHandler = {
    import org.broadinstitute.dsde.workbench.sam.model.ErrorReportJsonSupport._

    ExceptionHandler {
      case withErrorReport: WorkbenchExceptionWithErrorReport =>
        complete(withErrorReport.errorReport.statusCode.getOrElse(StatusCodes.InternalServerError), withErrorReport.errorReport)
      case e: Throwable =>
        complete(StatusCodes.InternalServerError, ErrorReport(e))
    }
  }

  // basis for this lifted from http://stackoverflow.com/questions/32475471/how-does-one-log-akka-http-client-requests
  private def logRequestResult(route: => Route) = {
    def entityAsString(entity: HttpEntity): Future[String] = {
      entity.dataBytes
        .map(_.decodeString(entity.contentType.charsetOption.getOrElse(HttpCharsets.`UTF-8`).value))
        .runWith(Sink.head)
    }

    def myLoggingFunction(logger: LoggingAdapter)(req: HttpRequest)(res: Any): Unit = {
      val entry = res match {
        case Complete(resp) =>
          val logLevel: LogLevel = resp.status.intValue / 100 match {
            case 5 => Logging.ErrorLevel
            case _ => Logging.DebugLevel
          }
          entityAsString(resp.entity).map(data => LogEntry(s"${req.method} ${req.uri}: ${resp.status} entity: $data", logLevel))
        case other =>
          Future.successful(LogEntry(s"$other", Logging.DebugLevel)) // I don't really know when this case happens
      }
      entry.map(_.logTo(logger))
    }
    DebuggingDirectives.logRequestResult(LoggingMagnet(log => myLoggingFunction(log)))(route)
  }


  def route: server.Route =
    logRequestResult {
      handleExceptions(myExceptionHandler) {
        get {
          pathSingleSlash {
            complete("hello")
          }
        }
      }
    }

  def statusCodeCreated[T](response: T): (StatusCode, T) = (StatusCodes.Created, response)

}

