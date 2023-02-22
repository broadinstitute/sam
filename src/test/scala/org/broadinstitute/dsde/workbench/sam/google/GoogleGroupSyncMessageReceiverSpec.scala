package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.FullyQualifiedPolicyIdFormat
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.scalatest.{MockitoSugar, ResetMocksAfterEachTest}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import spray.json._

import java.util.UUID

class GoogleGroupSyncMessageReceiverSpec extends AnyFlatSpecLike with Matchers with MockitoSugar with ResetMocksAfterEachTest {
  private val synchronizer = mock[GoogleGroupSynchronizer](RETURNS_SMART_NULLS)
  private val consumer = mock[AckReplyConsumer](RETURNS_SMART_NULLS)
  private val receiver = new GoogleGroupSyncMessageReceiver(synchronizer)
  private val testGroup = WorkbenchGroupName(UUID.randomUUID().toString)

  "GoogleGroupSyncMessageReceiver" should "ack group not found" in {
    when(synchronizer.synchronizeGroupMembers(ArgumentMatchers.eq(testGroup), ArgumentMatchers.eq(Set.empty), any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "not found"))))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testGroup.toJson.compactPrint)).build(), consumer)
    verify(consumer).ack()
  }

  it should "nack on error" in {
    when(synchronizer.synchronizeGroupMembers(ArgumentMatchers.eq(testGroup), ArgumentMatchers.eq(Set.empty), any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "other error"))))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testGroup.toJson.compactPrint)).build(), consumer)
    verify(consumer).nack()
  }

  it should "ack on completion" in {
    when(synchronizer.synchronizeGroupMembers(ArgumentMatchers.eq(testGroup), ArgumentMatchers.eq(Set.empty), any[SamRequestContext]))
      .thenReturn(IO.pure(Map(WorkbenchEmail(s"${testGroup.value}@foo.com") -> Seq.empty)))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testGroup.toJson.compactPrint)).build(), consumer)
    verify(consumer).ack()
  }

  it should "nack on completion with errors" in {
    when(synchronizer.synchronizeGroupMembers(ArgumentMatchers.eq(testGroup), ArgumentMatchers.eq(Set.empty), any[SamRequestContext]))
      .thenReturn(IO.pure(Map(WorkbenchEmail(s"${testGroup.value}@foo.com") -> Seq(SyncReportItem("added", "member email", Option(ErrorReport("failure")))))))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testGroup.toJson.compactPrint)).build(), consumer)
    verify(consumer).nack()

  }

  it should "parse policy id" in {
    val testPolicy = FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName("rt"), ResourceId("rid")), AccessPolicyName("policy"))
    val result = receiver.parseMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testPolicy.toJson.compactPrint)).build())
    result shouldBe testPolicy
  }

  it should "parse group id" in {
    val result = receiver.parseMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(testGroup.toJson.compactPrint)).build())
    result shouldBe testGroup
  }
}
