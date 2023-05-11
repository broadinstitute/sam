package org.broadinstitute.dsde.workbench.sam.matchers

import org.mockito.ArgumentMatcher

class AnyOfMatcher[T](expectedValues: Iterable[T]) extends ArgumentMatcher[T] {

  /** Use in conjunction with argThat.
    *
    * For example:
    *
    * import org.mockito.Mockito._ import org.mockito.ArgumentMatchers._
    *
    * val mockObj = mock(classOf[MyClass])
    *
    * when(mockObj.myMethod(argThat(new AnyOfMatcher(Seq("foo", "bar", "baz"))))).thenReturn("matched") mockObj.myMethod("bar")
    * verify(mockObj).myMethod(argThat(new AnyOfMatcher(Seq("foo", "bar", "baz"))))
    *
    * @param argument
    *   the value to match against any value from expectedValues
    * @return
    *   if the argument is equal to any of the expected values, the matcher returns true; otherwise, it returns false
    */
  override def matches(argument: T): Boolean = expectedValues.exists(_ == argument)
}
