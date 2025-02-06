package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, ServiceAccountKeyId}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class CachedKeySpec extends AnyFlatSpecLike with Matchers {

  behavior of "CachedKey"

  it should "construct from a GcsObjectName" in {
    val now = Instant.now()

    val gcsObjectName = GcsObjectName("path/path/myid", now)

    val cachedKey = CachedKey(gcsObjectName)

    cachedKey.timeCreated shouldBe now
    cachedKey.keyId shouldBe ServiceAccountKeyId("myid")
  }

  it should "calculate isBefore" in {
    val earliest = Instant.now()
    val middle = earliest.plusSeconds(1)
    val latest = earliest.plusSeconds(2)

    val cachedKey = new CachedKey(middle, ServiceAccountKeyId("id"))

    cachedKey.isBefore(latest) shouldBe true
    cachedKey.isBefore(middle) shouldBe false
    cachedKey.isBefore(earliest) shouldBe false
  }

  it should "calculate isAfterOrEqual" in {
    val earliest = Instant.now()
    val middle = earliest.plusSeconds(1)
    val latest = earliest.plusSeconds(2)

    val cachedKey = new CachedKey(middle, ServiceAccountKeyId("id"))

    cachedKey.isAfterOrEqual(latest) shouldBe false
    cachedKey.isAfterOrEqual(middle) shouldBe true
    cachedKey.isAfterOrEqual(earliest) shouldBe true
  }

}
