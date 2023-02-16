package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, TermsOfServiceComplianceStatus}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.mockito.{ArgumentMatcher, ArgumentMatchers}

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

  private def setAcceptedStateForAllTo(isAccepted: Boolean) =
    lenient()
      .doAnswer((i: InvocationOnMock) => IO.pure(TermsOfServiceComplianceStatus(i.getArgument[SamUser](0).id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(any[SamUser])

  private def setAcceptedStateForUserTo(samUser: SamUser, isAccepted: Boolean) = {
    val matchesUser = new ArgumentMatcher[SamUser] {
      override def matches(argument: SamUser): Boolean =
        argument.id.equals(samUser.id)
    }
    lenient()
      .doReturn(IO.pure(TermsOfServiceComplianceStatus(samUser.id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(ArgumentMatchers.argThat(matchesUser))
  }

  def build: TosService = tosService
}
