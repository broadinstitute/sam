package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.ActorSystem
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.GoogleUtilities
import org.broadinstitute.dsde.workbench.sam.Slow
import org.broadinstitute.dsde.workbench.sam.dataAccess.LastQuotaErrorDAO
import org.mockito.scalatest.MockitoSugar
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class CoordinatedBackoffHttpGoogleDirectoryDAOSpec extends AnyFreeSpec with Matchers with MockitoSugar {
  implicit val system = ActorSystem("CoordinatedBackoffHttpGoogleDirectoryDAOSpec")

  "CoordinatedBackoffHttpGoogleDirectoryDAO" - {
    "retryExponentially" - {
      "should not do the op when in backoff mode" taggedAs Slow in {
        val lastQuotaErrorDAOMock = mock[LastQuotaErrorDAO]
        when(lastQuotaErrorDAOMock.quotaErrorOccurredWithinDuration(any[Duration])).thenReturn(IO.pure(true))
        val googleDao = new CoordinatedBackoffHttpGoogleDirectoryDAO("", null, "", lastQuotaErrorDAOMock, retryStartingMillis = 1)

        val test = googleDao.retryExponentially(GoogleUtilities.RetryPredicates.whenUsageLimited) { () =>
          Future(fail("op should not have been called"))
        }

        Await.result(test, Duration.Inf) match {
          case Right(_) => fail("expected error")
          case Left(errors) =>
            errors.length shouldBe 8
            errors.forall(_ == googleDao.usageLimitedException)
        }
      }

      "records quota errors" in {
        val lastQuotaErrorDAOMock = mock[LastQuotaErrorDAO]
        when(lastQuotaErrorDAOMock.quotaErrorOccurredWithinDuration(any[Duration])).thenReturn(IO.pure(false))
        when(lastQuotaErrorDAOMock.recordQuotaError()).thenReturn(IO.pure(1))
        val googleDao = new CoordinatedBackoffHttpGoogleDirectoryDAO("", null, "", lastQuotaErrorDAOMock, retryStartingMillis = 1)

        val test = googleDao.retryExponentially(_ => false) { () =>
          Future.failed(googleDao.usageLimitedException)
        }

        Await.result(test, Duration.Inf) match {
          case Right(_) => fail("expected error")
          case Left(errors) =>
            errors.length shouldBe 1
            errors.forall(_ == googleDao.usageLimitedException)
        }

        verify(lastQuotaErrorDAOMock).recordQuotaError()
      }

      "should do the op when not in backoff mode" in {
        val lastQuotaErrorDAOMock = mock[LastQuotaErrorDAO]
        when(lastQuotaErrorDAOMock.quotaErrorOccurredWithinDuration(any[Duration])).thenReturn(IO.pure(false))
        val googleDao = new CoordinatedBackoffHttpGoogleDirectoryDAO("", null, "", lastQuotaErrorDAOMock, retryStartingMillis = 1)

        val expectedResult = "I did a thing"
        val test = googleDao.retryExponentially(GoogleUtilities.RetryPredicates.whenUsageLimited) { () =>
          Future.successful(expectedResult)
        }

        Await.result(test, Duration.Inf) match {
          case Right((l, result)) =>
            l shouldBe empty
            result shouldBe expectedResult
          case Left(errors) => fail("should not have had errors", errors.head)
        }
      }
    }
  }
}
