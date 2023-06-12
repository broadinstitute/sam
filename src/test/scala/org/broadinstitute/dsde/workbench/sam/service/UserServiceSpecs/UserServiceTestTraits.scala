package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.mockito.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, OptionValues}

// See: https://www.scalatest.org/user_guide/defining_base_classes
abstract class UserServiceTestTraits extends AnyFunSpec with Matchers with TestSupport with IdiomaticMockito with ScalaFutures with OptionValues with Inside {}
