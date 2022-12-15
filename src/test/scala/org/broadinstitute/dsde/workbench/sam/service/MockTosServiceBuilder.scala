package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doReturn}
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
    setAcceptedStateForUserTo(samUser.id, isAccepted)
    this
  }

  def withAcceptedStateForUser(userId: WorkbenchUserId, isAccepted: Boolean): MockTosServiceBuilder = {
    setAcceptedStateForUserTo(userId, isAccepted)
    this
  }

  private def setAcceptedStateForAllTo(isAccepted: Boolean) = {
    doReturn(IO(Option(isAccepted)))
      .when(tosService)
      .getTosStatus(any[WorkbenchUserId], any[SamRequestContext])
  }

  private def setAcceptedStateForUserTo(userId: WorkbenchUserId, isAccepted: Boolean) = {
    doReturn(IO(Option(isAccepted)))
      .when(tosService)
      .getTosStatus(ArgumentMatchers.eq(userId), any[SamRequestContext])
  }

  def build: TosService = tosService
}
