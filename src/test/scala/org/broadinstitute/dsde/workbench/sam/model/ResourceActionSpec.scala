package org.broadinstitute.dsde.workbench.sam.model

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class ResourceActionSpec extends AnyFlatSpecLike with Matchers {
  behavior of "ResourceAction"

  it should "treat resource actions with different cases as equal" in {
    val action1 = ResourceAction("share_policy::READER")
    val action2 = ResourceAction("share_policy::reader")
    action1 shouldEqual action2
  }
}
