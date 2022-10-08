package org.springframework.util

import javax.annotation.Nullable

object StringUtils {
  def isEmpty(@Nullable str: Any): Boolean = str == null || "" == str

  def hasText(@Nullable str: String): Boolean = str != null && str.length > 0 && containsText(str)

  private def containsText(str: CharSequence): Boolean = {
    val strLen = str.length
    for (i <- 0 until strLen) {
      if (!Character.isWhitespace(str.charAt(i))) return true
    }
    false
  }
}
