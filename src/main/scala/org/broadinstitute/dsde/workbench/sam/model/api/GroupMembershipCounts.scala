package org.broadinstitute.dsde.workbench.sam.model.api

import spray.json.DefaultJsonProtocol._
import spray.json._

object GroupMembershipCounts {
  implicit val GroupMembershipCountsFormat: RootJsonFormat[GroupMembershipCounts] = jsonFormat2(GroupMembershipCounts.apply)
}
final case class GroupMembershipCounts(
    directSynchronized: Int,
    totalSynchronized: Int
)
