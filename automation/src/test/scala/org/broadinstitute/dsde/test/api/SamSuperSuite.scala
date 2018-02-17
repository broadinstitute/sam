package org.broadinstitute.dsde.test.api

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.fixture.GPAllocSuperFixture
import org.scalatest.Suites

class SamSuperSuite extends Suites(
  new SamApiSpec) with GPAllocSuperFixture {

}
