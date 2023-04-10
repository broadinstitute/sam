package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import cats.effect.unsafe.implicits.global
import com.google.api.client.googleapis.json.{GoogleJsonError, GoogleJsonResponseException}
import com.google.api.client.http.{HttpHeaders, HttpResponseException}
import org.broadinstitute.dsde.workbench.google.GoogleCredentialModes.GoogleCredentialMode
import org.broadinstitute.dsde.workbench.google.GoogleUtilities.RetryPredicates.whenUsageLimited
import org.broadinstitute.dsde.workbench.google.HttpGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.dataAccess.LastQuotaErrorDAO

import java.util.Collections
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

class CoordinatedBackoffHttpGoogleDirectoryDAO(
    appName: String,
    googleCredentialMode: GoogleCredentialMode,
    workbenchMetricBaseName: String,
    lastQuotaErrorDAO: LastQuotaErrorDAO,
    maxPageSize: Int = 200,
    val backoffDuration: Option[Duration] = None
)(implicit system: ActorSystem, executionContext: ExecutionContext)
    extends HttpGoogleDirectoryDAO(appName, googleCredentialMode, workbenchMetricBaseName, maxPageSize) {

  override def retryExponentially[T](pred: Predicate[Throwable], failureLogMessage: String)(op: () => Future[T])(implicit
      executionContext: ExecutionContext
  ): RetryableFuture[T] =
    super.retryExponentially(pred, failureLogMessage) { () =>
      lastQuotaErrorDAO.quotaErrorOccurredWithinDuration(backoffDuration.getOrElse(2 seconds)).unsafeToFuture().flatMap {
        case true => Future.failed(usageLimitedException)
        case false =>
          op().recoverWith {
            case t if whenUsageLimited(t) => lastQuotaErrorDAO.recordQuotaError().unsafeToFuture().flatMap(_ => Future.failed(t))
          }
      }
    }

  private[google] def usageLimitedException = {
    val errorInfo = new GoogleJsonError.ErrorInfo()
    errorInfo.setDomain("usageLimits")
    errorInfo.setMessage("Sam in backoff mode, did not actually call google")
    val googleJsonError = new GoogleJsonError()
    googleJsonError.setCode(403)
    googleJsonError.setErrors(Collections.singletonList(errorInfo))
    new GoogleJsonResponseException(
      new HttpResponseException.Builder(403, "403 Forbidden", new HttpHeaders()),
      googleJsonError
    )
  }
}
