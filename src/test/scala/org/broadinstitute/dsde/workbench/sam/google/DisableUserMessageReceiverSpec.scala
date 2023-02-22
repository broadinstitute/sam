package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.{UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.mockito.scalatest.{MockitoSugar, ResetMocksAfterEachTest}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class DisableUserMessageReceiverSpec extends AnyFlatSpecLike with Matchers with TestSupport with MockitoSugar with ResetMocksAfterEachTest {
  private val userService = mock[UserService](RETURNS_SMART_NULLS)
  private val consumer = mock[AckReplyConsumer](RETURNS_SMART_NULLS)
  private val receiver = new DisableUserMessageReceiver(userService)
  private val userId = WorkbenchUserId(UUID.randomUUID().toString)

  "DisableUserMessageReceiver" should "ack when user is disabled" in {
    when(userService.disableUser(ArgumentMatchers.eq(userId), any[SamRequestContext]))
      .thenReturn(IO.pure(Option(UserStatus(UserStatusDetails(userId, WorkbenchEmail("email")), Map.empty))))

    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(userId.value)).build(), consumer)

    verify(consumer).ack()
  }

  it should "ack when user not found" in {
    when(userService.disableUser(ArgumentMatchers.eq(userId), any[SamRequestContext])).thenReturn(IO.pure(None))

    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(userId.value)).build(), consumer)

    verify(consumer).ack()
  }

  it should "nack on failure" in {
    when(userService.disableUser(ArgumentMatchers.eq(userId), any[SamRequestContext])).thenReturn(IO.raiseError(new Exception("boom")))

    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(userId.value)).build(), consumer)

    verify(consumer).nack()
  }
}
