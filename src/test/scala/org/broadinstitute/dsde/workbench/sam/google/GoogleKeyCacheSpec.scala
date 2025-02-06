package org.broadinstitute.dsde.workbench.sam.google

import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountKeyId
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.CachedKey
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}

class GoogleKeyCacheSpec extends AnyFlatSpecLike with Matchers {

  def newKeyCache(iamDAO: GoogleIamDAO = new MockGoogleIamDAO): GoogleKeyCache =
    new GoogleKeyCache(
      TestSupport.distributedLock,
      iamDAO,
      new MockGoogleStorageDAO,
      FakeGoogleStorageInterpreter,
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
      val idealKey = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "value", ServiceAccountKeyId("ideal-1"))
      val retiredKey = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "value", ServiceAccountKeyId("retired-1"))

      val input = List(nascentKey, idealKey, retiredKey)
      val actual = newKeyCache().searchCachedKeys(input, withinLock = withinLock)
      actual should contain(idealKey)
    }

    it should s"return an ideally-aged key when it's the only one to exist (withinLock=$withinLock)" in {
      val idealKey = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "value", ServiceAccountKeyId("ideal-1"))

      val input = List(idealKey)
      val actual = newKeyCache().searchCachedKeys(input, withinLock = withinLock)
      actual should contain(idealKey)
    }

    it should s"return the newest of multiple ideal keys (withinLock=$withinLock)" in {
      val nascentKey = new CachedKey(Instant.now.minusSeconds(10), "value", ServiceAccountKeyId("nascent-1"))
      val idealKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 10), "value", ServiceAccountKeyId("ideal-1"))
      val idealKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 20), "value", ServiceAccountKeyId("ideal-2"))
      val idealKey3 = new CachedKey(Instant.now.minusSeconds(idealSecondsStart + 30), "value", ServiceAccountKeyId("ideal-3"))
      val retiredKey = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "value", ServiceAccountKeyId("retired-1"))

      val input = List(nascentKey, idealKey3, idealKey2, idealKey1, retiredKey)
      val actual = newKeyCache().searchCachedKeys(input, withinLock = withinLock)
      actual should contain(idealKey1)
    }

    it should s"return the newest retired key when only retired and nascent keys exist (withinLock=$withinLock)" in {
      val nascentKey1 = new CachedKey(Instant.now.minusSeconds(10), "value", ServiceAccountKeyId("nascent-1"))
      val nascentKey2 = new CachedKey(Instant.now.minusSeconds(20), "value", ServiceAccountKeyId("nascent-1"))
      val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "value", ServiceAccountKeyId("retired-1"))
      val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "value", ServiceAccountKeyId("retired-1"))

      val input = List(retiredKey2, nascentKey2, retiredKey1, nascentKey1)
      val actual = newKeyCache().searchCachedKeys(input, withinLock = withinLock)
      actual should contain(retiredKey1)
    }

    it should s"return the oldest nascent key when only nascent keys exist (withinLock=$withinLock)" in {
      val nascentKey1 = new CachedKey(Instant.now.minusSeconds(10), "value", ServiceAccountKeyId("nascent-1"))
      val nascentKey2 = new CachedKey(Instant.now.minusSeconds(20), "value", ServiceAccountKeyId("nascent-1"))

      val input = List(nascentKey2, nascentKey1)
      val actual = newKeyCache().searchCachedKeys(input, withinLock = withinLock)
      actual should contain(nascentKey2)
    }
  }

  behavior of "GoogleKeyCache.searchCachedKeys(), when outside a lock"

  it should "return None when only retired keys exist" in {
    // this case triggers creation of a new key, so it returns None when not within a lock
    val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "value", ServiceAccountKeyId("retired-1"))
    val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "value", ServiceAccountKeyId("retired-1"))

    val input = List(retiredKey2, retiredKey1)
    val actual = newKeyCache().searchCachedKeys(input, withinLock = false)
    actual shouldBe None
  }

  it should "return None when no keys exist in cache" in {
    // this case triggers creation of a new key, so it returns None when not within a lock
    val input: List[CachedKey] = List()
    val actual = newKeyCache().searchCachedKeys(input, withinLock = false)
    actual shouldBe None
  }

  behavior of "GoogleKeyCache.searchCachedKeys(), when within a lock"

  it should "return None when only retired keys exist" in {
    // this case triggers creation of a new key, so it returns None when not within a lock
    val retiredKey1 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 10), "value", ServiceAccountKeyId("retired-1"))
    val retiredKey2 = new CachedKey(Instant.now.minusSeconds(idealSecondsEnd + 20), "value", ServiceAccountKeyId("retired-1"))

    val input = List(retiredKey2, retiredKey1)
    val actual = newKeyCache().searchCachedKeys(input, withinLock = false)
    actual shouldBe defined
  }

  it should "return a newly-created key when no keys exist in cache" in {
    val input: List[CachedKey] = List()
    val actual = newKeyCache().searchCachedKeys(input, withinLock = true)
    actual shouldBe defined
  }

}
