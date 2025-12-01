package com.iodesystems.ts

import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult

class Scanner(private val config: Config) {

    fun scan(): ScanResult {
        return ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .enableFieldInfo()
            .enableMethodInfo()
            .ignoreFieldVisibility()
            .acceptPackages(*config.basePackages.toTypedArray())
            .scan()
    }
}
