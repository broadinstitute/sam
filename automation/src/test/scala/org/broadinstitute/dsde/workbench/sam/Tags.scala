package org.broadinstitute.dsde.workbench.sam

import org.scalatest.Tag

// Tests that should be converted to Unit or Functional tests in Sam core
object ConvertToUnitTest extends Tag("ConvertToUnitTest")

// Tests that should be in the Orchestration Test Suite
object OrchestrationTest extends Tag("OrchestrationTest")

// Tests that require upstream and downstream services in order to test
object E2ETest extends Tag("E2ETest")

// Tests that require an "integrated" environment including downstream services, but NO upstream services
object IntegrationTest extends Tag("IntegrationTest")