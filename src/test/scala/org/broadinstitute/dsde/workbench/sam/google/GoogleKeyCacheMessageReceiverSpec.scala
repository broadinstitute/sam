package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import com.google.api.client.googleapis.json.{GoogleJsonError, GoogleJsonResponseException}
import com.google.api.client.http.{HttpHeaders, HttpResponseException}
import com.google.cloud.pubsub.v1.AckReplyConsumer
import com.google.protobuf.ByteString
import com.google.pubsub.v1.PubsubMessage
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountDisplayName, ServiceAccountKey, ServiceAccountKeyId, ServiceAccountPrivateKeyData, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.scalatest.{MockitoSugar, ResetMocksAfterEachTest}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.{Collections, UUID}
import scala.concurrent.Future

class GoogleKeyCacheMessageReceiverSpec extends AnyFlatSpecLike with Matchers with MockitoSugar with ResetMocksAfterEachTest {
  private val iamDao = mock[GoogleIamDAO](RETURNS_SMART_NULLS)
  private val consumer = mock[AckReplyConsumer](RETURNS_SMART_NULLS)
  private val receiver = new GoogleKeyCacheMessageReceiver(iamDao)
  private val project = Generator.genGoogleProject.sample.get
  private val serviceAccountEmail = Generator.genServiceAccountEmail.sample.get
  private val keyId = ServiceAccountKeyId(UUID.randomUUID().toString)
  private val message = s"""{"name": "${project.value}/${serviceAccountEmail.value}/${keyId.value}"}"""

  "GoogleKeyCacheMessageReceiver" should "ack key removed" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail))
      .thenReturn(Future.successful(Option(ServiceAccount(ServiceAccountSubjectId(""), serviceAccountEmail, ServiceAccountDisplayName("")))))
    when(iamDao.listServiceAccountKeys(project, serviceAccountEmail))
      .thenReturn(Future.successful(Seq(ServiceAccountKey(keyId, ServiceAccountPrivateKeyData(""), None, None))))
    when(iamDao.removeServiceAccountKey(project, serviceAccountEmail, keyId)).thenReturn(Future.successful(()))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).ack()
  }

  it should "ack key not found" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail))
      .thenReturn(Future.successful(Option(ServiceAccount(ServiceAccountSubjectId(""), serviceAccountEmail, ServiceAccountDisplayName("")))))
    when(iamDao.listServiceAccountKeys(project, serviceAccountEmail)).thenReturn(Future.successful(Seq.empty))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).ack()
  }

  it should "ack service account not found" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail)).thenReturn(Future.successful(None))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).ack()
  }

  it should "ack service account access denied" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail)).thenReturn(Future.failed(googleError(StatusCodes.Forbidden)))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).ack()
  }

  it should "nack on findServiceAccount errors" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail)).thenReturn(Future.failed(googleError(StatusCodes.TooManyRequests)))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).nack()
  }

  it should "nack on listServiceAccountKeys errors" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail))
      .thenReturn(Future.successful(Option(ServiceAccount(ServiceAccountSubjectId(""), serviceAccountEmail, ServiceAccountDisplayName("")))))
    when(iamDao.listServiceAccountKeys(project, serviceAccountEmail)).thenReturn(Future.failed(googleError(StatusCodes.TooManyRequests)))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).nack()
  }

  it should "nack on removeServiceAccountKey errors" in {
    when(iamDao.findServiceAccount(project, serviceAccountEmail))
      .thenReturn(Future.successful(Option(ServiceAccount(ServiceAccountSubjectId(""), serviceAccountEmail, ServiceAccountDisplayName("")))))
    when(iamDao.listServiceAccountKeys(project, serviceAccountEmail))
      .thenReturn(Future.successful(Seq(ServiceAccountKey(keyId, ServiceAccountPrivateKeyData(""), None, None))))
    when(iamDao.removeServiceAccountKey(project, serviceAccountEmail, keyId)).thenReturn(Future.failed(googleError(StatusCodes.TooManyRequests)))
    receiver.receiveMessage(PubsubMessage.newBuilder().setData(ByteString.copyFromUtf8(message)).build(), consumer)
    verify(consumer).nack()
  }

  private def googleError(errorCode: StatusCodes.ClientError) = {
    val googleJsonError = new GoogleJsonError()
    googleJsonError.setCode(errorCode.intValue)
    googleJsonError.setErrors(Collections.singletonList(new GoogleJsonError.ErrorInfo()))
    val deniedException = new GoogleJsonResponseException(
      new HttpResponseException.Builder(errorCode.intValue, errorCode.reason, new HttpHeaders()),
      googleJsonError
    )
    deniedException
  }
}
