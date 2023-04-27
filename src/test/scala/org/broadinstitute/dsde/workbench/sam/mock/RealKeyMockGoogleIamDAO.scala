package org.broadinstitute.dsde.workbench.sam.mock

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.gson.stream.{JsonReader, JsonWriter}
import com.google.gson.{FieldNamingPolicy, GsonBuilder, TypeAdapter}
import org.bouncycastle.jcajce.provider.asymmetric.rsa.BCRSAPrivateCrtKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.{JcaPEMWriter, JcaPKCS8Generator}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId, ServiceAccountPrivateKeyData}

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.security.{KeyPairGenerator, Security}
import java.time.{Duration => JavaDuration, Instant}
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleIamDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.mock.RealKeyMockGoogleIamDAO.generateNewRealKey

import java.util.{Base64, UUID}
import scala.concurrent.Future
import scala.util.Using

object RealKeyMockGoogleIamDAO {

  def generateNewRealKey(serviceAccountEmail: WorkbenchEmail): (ServiceAccountKeyId, String) = {
    Security.addProvider(new BouncyCastleProvider)

    // Create the public and private keys
    val keyGen = KeyPairGenerator.getInstance("RSA", "BC")
    keyGen.initialize(2048)
    val pair = keyGen.genKeyPair()

    val keyId = ServiceAccountKeyId(UUID.randomUUID().toString)
    val serviceAccountCredentials = ServiceAccountCredentials
      .newBuilder()
      .setServiceAccountUser("testUser")
      .setClientEmail(serviceAccountEmail.value)
      .setClientId("12345678")
      .setPrivateKey(pair.getPrivate)
      .setPrivateKeyId(keyId.value)
      .build()

    val gson = new GsonBuilder()
      .registerTypeAdapter(classOf[JavaDuration], new DurationAdapter)
      .registerTypeAdapter(classOf[BCRSAPrivateCrtKey], new BCRSAPrivateCrtKeyAdapter)
      .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
      .create()
    val keyJsonTree = gson.toJsonTree(serviceAccountCredentials)
    keyJsonTree.getAsJsonObject.addProperty("type", "service_account")
    (keyId, gson.toJson(keyJsonTree))
  }

  class DurationAdapter extends TypeAdapter[JavaDuration] {
    override def write(writer: JsonWriter, value: JavaDuration): Unit =
      writer.value(value.toMillis)

    override def read(in: JsonReader): JavaDuration = throw new NotImplementedError("No reading here")
  }

  class BCRSAPrivateCrtKeyAdapter extends TypeAdapter[BCRSAPrivateCrtKey] {
    override def write(writer: JsonWriter, value: BCRSAPrivateCrtKey): Unit = {
      val sw = new StringWriter()
      Using(new JcaPEMWriter(sw)) { pw =>
        pw.writeObject(new JcaPKCS8Generator(value, null))
      }
      writer.value(sw.toString)
    }

    override def read(in: JsonReader): BCRSAPrivateCrtKey = throw new NotImplementedError("No reading here")
  }
}

class RealKeyMockGoogleIamDAO extends MockGoogleIamDAO {

  override def createServiceAccountKey(serviceAccountProject: GoogleProject, serviceAccountEmail: WorkbenchEmail): Future[ServiceAccountKey] = {
    val (keyId, keyJson) = generateNewRealKey(serviceAccountEmail)
    val key = ServiceAccountKey(
      keyId,
      ServiceAccountPrivateKeyData(Base64.getEncoder.encodeToString(keyJson.getBytes(StandardCharsets.UTF_8))),
      Some(Instant.now),
      Some(Instant.now.plusSeconds(300))
    )
    serviceAccountKeys(serviceAccountEmail) += keyId -> key
    Future.successful(key)
  }
}
