package org.broadinstitute.dsde.workbench.sam.matchers

import org.mockito.ArgumentMatcher

class AnyOfMatcher[T](expectedValues: Iterable[T]) extends ArgumentMatcher[T] {
  override def matches(argument: T): Boolean = expectedValues.exists(_ == argument)
}
