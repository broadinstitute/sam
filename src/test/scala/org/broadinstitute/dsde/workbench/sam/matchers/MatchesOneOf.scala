package org.broadinstitute.dsde.workbench.sam.matchers

import org.mockito.ArgumentMatcher

class MatchesOneOf[T](possibleValues: Iterable[T]) extends ArgumentMatcher[T] {

  /** Use in conjunction with argThat.
    *
    * Example usage:
    * ```
    * import org.mockito.Mockito._
    * import org.mockito.ArgumentMatchers._
    *
    * val mockObj = mock(classOf[MyClass])
    *
    * when(mockObj.myMethod(argThat(MatchesOneOf(Seq("foo", "bar", "baz")))))
    *   .thenReturn("matched") mockObj.myMethod("bar")
    * verify(mockObj).myMethod(argThat(MatchesOneOf(Seq("foo", "bar", "baz"))))
    * ```
    *
    * @param argument
    *   the value to match against any value from possibleValues
    * @return
    *   if the argument is equal to any of the expected values, the matcher returns true; otherwise, it returns false
    */
  override def matches(argument: T): Boolean = possibleValues.exists(_ == argument)
}

object MatchesOneOf {
  def apply[T](possibleValues: Iterable[T]): MatchesOneOf[T] = new MatchesOneOf[T](possibleValues)
}
