package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId, ServiceAccountPrivateKeyData}
import org.broadinstitute.dsde.workbench.sam.Generator.genPetServiceAccount
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.CachedKey
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.{times, when}
import org.mockito.MockitoSugar.mock
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import scala.concurrent.Future
import scala.util.Random
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.GcsBlobName
import org.broadinstitute.dsde.workbench.google2.Generators.utf8Charset
import org.scalatest.BeforeAndAfterAll

class GoogleKeyCacheSpec extends AnyFlatSpecLike with Matchers with BeforeAndAfterAll {

  val pet = genPetServiceAccount.sample.get

  val storageInterp = FakeGoogleStorageInterpreter

  override protected def beforeAll(): Unit =
    // set up google storage for the test cases below
    List("nascent-1", "nascent-2", "ideal-1", "ideal-2", "ideal-3", "retired-1", "retired-2") foreach { obj =>
      (Stream.emits(obj.getBytes(utf8Charset)).covary[IO] through storageInterp.streamUploadBlob(
        TestSupport.googleServicesConfig.googleKeyCacheConfig.bucketName,
        GcsBlobName(obj)
      )).compile.drain.unsafeRunSync()
    }

  def newKeyCache(iamDAO: GoogleIamDAO = new MockGoogleIamDAO): GoogleKeyCache =
    new GoogleKeyCache(
      TestSupport.distributedLock,
      iamDAO,
      new MockGoogleStorageDAO,
      storageInterp,
      new MockGooglePubSubDAO,
      TestSupport.googleServicesConfig,
      TestSupport.petServiceAccountConfig
    )

  // number of seconds before which a key is considered nascent
  private val idealSecondsStart = TestSupport.googleServicesConfig.googleKeyCacheConfig.nascentKeyMinAgeMinutes * 60L
  // number of seconds after which a key is considered retired
  private val idealSecondsEnd = TestSupport.googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge * 24L * 60 * 60

  behavior of "GoogleKeyCache.searchCachedKeys()"

  // these tests behave the same both within and outside a lock
  List(false, true) foreach { withinLock =>
    it should s"return an ideally-aged key even when others exist (withinLock=$withinLock)" in {
      val nascentKey = new CachedKey(Instant.now.minusSeconds(10), "value", ServiceAccountKeyId("nascent-1"))
      val idealKey = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "ideal-1", ServiceAccountKeyId("ideal-1"))
      val retiredKey = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "retired-1", ServiceAccountKeyId("retired-1"))

      val input = List(nascentKey, idealKey, retiredKey)
      val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = withinLock).unsafeRunSync()
      actual should contain("ideal-1")
    }

    it should s"return an ideally-aged key when it's the only one to exist (withinLock=$withinLock)" in {
      val idealKey = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "ideal-1", ServiceAccountKeyId("ideal-1"))

      val input = List(idealKey)
      val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = withinLock).unsafeRunSync()
      actual should contain("ideal-1")
    }

    it should s"return the newest of multiple ideal keys (withinLock=$withinLock)" in {
      val nascentKey = new CachedKey(Instant.now.minusSeconds(10), "value", ServiceAccountKeyId("nascent-1"))
      val idealKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "ideal-1", ServiceAccountKeyId("ideal-1"))
      val idealKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 20), "ideal-2", ServiceAccountKeyId("ideal-2"))
      val idealKey3 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 30), "ideal-3", ServiceAccountKeyId("ideal-3"))
      val retiredKey = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "retired-1", ServiceAccountKeyId("retired-1"))

      val input = List(nascentKey, idealKey3, idealKey2, idealKey1, retiredKey)
      val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = withinLock).unsafeRunSync()
      actual should contain("ideal-1")
    }

    it should s"return the newest retired key when only retired and nascent keys exist (withinLock=$withinLock)" in {
      val nascentKey1 = new CachedKey(Instant.now.minusSeconds(10), "nascent-1", ServiceAccountKeyId("nascent-1"))
      val nascentKey2 = new CachedKey(Instant.now.minusSeconds(20), "nascent-2", ServiceAccountKeyId("nascent-2"))
      val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "retired-1", ServiceAccountKeyId("retired-1"))
      val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "retired-2", ServiceAccountKeyId("retired-2"))

      val input = List(retiredKey2, nascentKey2, retiredKey1, nascentKey1)
      val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = withinLock).unsafeRunSync()
      actual should contain("retired-1")
    }

    it should s"return the oldest nascent key when only nascent keys exist (withinLock=$withinLock)" in {
      val nascentKey1 = new CachedKey(Instant.now.minusSeconds(10), "nascent-1", ServiceAccountKeyId("nascent-1"))
      val nascentKey2 = new CachedKey(Instant.now.minusSeconds(20), "nascent-2", ServiceAccountKeyId("nascent-2"))

      val input = List(nascentKey2, nascentKey1)
      val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = withinLock).unsafeRunSync()
      actual should contain("nascent-2")
    }
  }

  behavior of "GoogleKeyCache.searchCachedKeys(), when outside a lock"

  it should "return None when only retired keys exist" in {
    // this case triggers creation of a new key, so it returns None when not within a lock
    val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "retired-1", ServiceAccountKeyId("retired-1"))
    val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "retired-2", ServiceAccountKeyId("retired-2"))

    val input = List(retiredKey2, retiredKey1)
    val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = false).unsafeRunSync()
    actual shouldBe None
  }

  it should "return None when no keys exist in cache" in {
    // this case triggers creation of a new key, so it returns None when not within a lock
    val input: List[CachedKey] = List()
    val actual = newKeyCache().searchCachedKeys(pet, input, withinLock = false).unsafeRunSync()
    actual shouldBe None
  }

  behavior of "GoogleKeyCache.searchCachedKeys(), when within a lock"

  it should "return None when only retired keys exist" in {
    val mockIamDao = mock[GoogleIamDAO]
    val mockKeyDataString = s"abcdefg:${System.currentTimeMillis}${Random.nextLong()}"
    val mockKeyData = ServiceAccountPrivateKeyData(
      Base64.getEncoder
        .encodeToString(mockKeyDataString.getBytes(StandardCharsets.UTF_8))
    )
    when(mockIamDao.createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail]))
      .thenReturn(
        Future.successful(
          ServiceAccountKey(
            ServiceAccountKeyId("new-id"),
            mockKeyData,
            None,
            None
          )
        )
      )
    when(mockIamDao.listUserManagedServiceAccountKeys(any[GoogleProject], any[WorkbenchEmail]))
      .thenReturn(Future.successful(Seq()))
    val keyCache = newKeyCache(mockIamDao)

    Mockito
      .verify(mockIamDao, times(0))
      .createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail])

    // this case triggers creation of a new key, so it returns None when not within a lock
    val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "retired-1", ServiceAccountKeyId("retired-1"))
    val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "retired-2", ServiceAccountKeyId("retired-2"))

    val input = List(retiredKey2, retiredKey1)
    val actual = keyCache.searchCachedKeys(pet, input, withinLock = true).unsafeRunSync()
    actual should contain("retired-1")
    Mockito
      .verify(mockIamDao, times(1))
      .createServiceAccountKey(pet.id.project, pet.serviceAccount.email)
  }

  it should "return a newly-created key when no keys exist in cache" in {
    val mockIamDao = mock[GoogleIamDAO]
    val mockKeyDataString = s"abcdefg:${System.currentTimeMillis}${Random.nextLong()}"
    val mockKeyData = ServiceAccountPrivateKeyData(
      Base64.getEncoder
        .encodeToString(mockKeyDataString.getBytes(StandardCharsets.UTF_8))
    )
    when(mockIamDao.createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail]))
      .thenReturn(
        Future.successful(
          ServiceAccountKey(
            ServiceAccountKeyId("new-id"),
            mockKeyData,
            None,
            None
          )
        )
      )
    when(mockIamDao.listUserManagedServiceAccountKeys(any[GoogleProject], any[WorkbenchEmail]))
      .thenReturn(Future.successful(Seq()))
    val keyCache = newKeyCache(mockIamDao)

    Mockito
      .verify(mockIamDao, times(0))
      .createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail])

    val input: List[CachedKey] = List()
    val actual = keyCache.searchCachedKeys(pet, input, withinLock = true).unsafeRunSync()
    Mockito
      .verify(mockIamDao, times(1))
      .createServiceAccountKey(pet.id.project, pet.serviceAccount.email)
    actual shouldBe defined
    actual.get shouldBe mockKeyDataString
  }

}
