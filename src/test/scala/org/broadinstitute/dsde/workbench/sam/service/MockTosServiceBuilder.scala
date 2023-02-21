package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.model.{SamUser, TermsOfServiceComplianceStatus}
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doAnswer, doReturn}
import org.scalatestplus.mockito.MockitoSugar.mock

case class MockTosServiceBuilder() {
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
    doAnswer(i => IO.pure(TermsOfServiceComplianceStatus(i.getArgument[SamUser](0).id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(any[SamUser])

  private def setAcceptedStateForUserTo(samUser: SamUser, isAccepted: Boolean) = {
    val matchesUser = new ArgumentMatcher[SamUser] {
      override def matches(argument: SamUser): Boolean =
        argument.id.equals(samUser.id)
    }
    doReturn(IO.pure(TermsOfServiceComplianceStatus(samUser.id, isAccepted, isAccepted)))
      .when(tosService)
      .getTosComplianceStatus(ArgumentMatchers.argThat(matchesUser))
  }

  def build: TosService = tosService
}
