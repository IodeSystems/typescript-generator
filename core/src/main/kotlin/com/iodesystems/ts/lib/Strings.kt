package com.iodesystems.ts.lib

object Strings {

    fun String.stripPrefix(prefix: String): String {
        return if (this.startsWith(prefix)) {
            this.substring(prefix.length)
        } else {
            this
        }
    }

}