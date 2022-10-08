package org.springframework.util

import javax.annotation.Nullable

object Assert {

  def isNull(@Nullable `object`: Any, message: String): Unit = {
    if (`object` != null) throw new IllegalArgumentException(message)
  }

  def notNull(@Nullable `object`: Any, message: String): Unit = {
    if (`object` == null) throw new IllegalArgumentException(message)
  }
}
