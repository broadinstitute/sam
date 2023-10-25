package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.{SamUserTos, TermsOfServiceComplianceStatus}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.mockito.{ArgumentMatcher, ArgumentMatchers}

import java.time.Instant

case class MockTosServiceBuilder() extends MockitoSugar {
  private val tosService = mock[TosService](RETURNS_SMART_NULLS)

  // Default to nobody having accepted
  setAcceptedStateForAllTo(false)

  def withAllAccepted(): MockTosServiceBuilder = {
    setAcceptedStateForAllTo(true)
    this
  }

  def withNoneAccepted(): MockTosServiceBuilder = {
    setAcceptedStateForAllTo(false)
    this
  }

  def withAcceptedStateForUser(samUser: SamUser, isAccepted: Boolean): MockTosServiceBuilder = {
    setAcceptedStateForUserTo(samUser, isAccepted)
    this
  }

  private def setAcceptedStateForAllTo(isAccepted: Boolean) = {
    lenient()
      .doAnswer((i: InvocationOnMock) => IO.pure(TermsOfServiceComplianceStatus(i.getArgument[SamUser](0).id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(any[SamUser], any[SamRequestContext])

    lenient()
      .doReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"")(new ErrorReportSource("MockTosServiceBuilder")))))
      .when(tosService)
      .getTermsOfServiceDetails(any[WorkbenchUserId], any[SamUser], any[SamRequestContext])
  }

  private def setAcceptedStateForUserTo(samUser: SamUser, isAccepted: Boolean, version: String = "v1") = {
    val matchesUser = new ArgumentMatcher[SamUser] {
      override def matches(argument: SamUser): Boolean =
        argument.id.equals(samUser.id)
    }
    lenient()
      .doReturn(IO.pure(TermsOfServiceComplianceStatus(samUser.id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(ArgumentMatchers.argThat(matchesUser), any[SamRequestContext])

    val action = if (isAccepted) "accepted" else "rejected"
    val rightNow = Instant.now
    lenient()
      .doReturn(IO.pure(SamUserTos(samUser.id, version, action, rightNow)))
      .when(tosService)
      .getTermsOfServiceDetails(ArgumentMatchers.eq(samUser.id), any[SamUser], any[SamRequestContext])
  }

  def build: TosService = tosService
}
