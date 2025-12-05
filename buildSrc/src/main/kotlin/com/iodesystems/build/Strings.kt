package com.iodesystems.build

object Strings {
  fun String.quoteDir(): String {
    // Escape & quote
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
  }
}
