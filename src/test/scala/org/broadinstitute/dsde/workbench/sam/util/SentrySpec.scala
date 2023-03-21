package org.broadinstitute.dsde.workbench.sam.util

import akka.http.scaladsl.model.StatusCodes
import io.sentry.{Hint, SentryEvent}
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.util.Sentry.filterException
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class SentrySpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach with TestSupport {

  "Sentry" - {
    "event filtering" - {
      "should drop events based on status codes and statusCodesToSkip" in {
        val throwable: Throwable = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "test 123"))
        val event = new SentryEvent(throwable)

        filterException(event, new Hint()) shouldEqual null
      }

      "should not events when status code is not in statusCodesToSkip" in {
        val throwable: Throwable = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "test 123"))
        val event = new SentryEvent(throwable)

        filterException(event, new Hint()) shouldEqual event
      }
    }
  }

}
