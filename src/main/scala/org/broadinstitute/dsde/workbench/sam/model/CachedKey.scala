package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, ServiceAccountKeyId}

import java.time.Instant
import scala.util.matching.Regex

object CachedKey {
  val keyPathPattern: Regex = """([^/]+)/([^/]+)/([^/]+)""".r

  def apply(gcsObject: GcsObjectName): CachedKey = {
    val timeCreated = gcsObject.timeCreated
    val keyId = gcsObject.value match { case keyPathPattern(_, _, keyId) => ServiceAccountKeyId(keyId) }
    new CachedKey(timeCreated, gcsObject.value, keyId)
  }
}

case class CachedKey(timeCreated: Instant, value: String, keyId: ServiceAccountKeyId) {

  def isBefore(other: Instant): Boolean = timeCreated.isBefore(other)

  def isAfterOrEqual(other: Instant): Boolean = timeCreated.isAfter(other) || timeCreated.equals(other)

}
