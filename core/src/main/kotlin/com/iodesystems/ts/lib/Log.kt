package com.iodesystems.ts.lib

import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger

object Log {
    fun Any.logger(): Logger {
        // if we are a companion object, we need to get the logger for the enclosing class
        return getLogger(
            if (this::class.java.name.contains("Companion")) this::class.java.enclosingClass else this::class.java
        )
    }
}