package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.scalatest.{Inside, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

// See: https://www.scalatest.org/user_guide/defining_base_classes
abstract class UserServiceTestTraits
  extends AnyFunSpec
    with Matchers
    with TestSupport
    with MockitoSugar
    with ScalaFutures
    with OptionValues
    with Inside
