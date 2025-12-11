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
            .enableSystemJarsAndModules()
            .enableExternalClasses()
            .enableInterClassDependencies()
            .let { classGraph ->
                if (config.classPathUrls.isNotEmpty()) {
                    classGraph.overrideClasspath(config.classPathUrls)
                } else classGraph
            }
            .acceptPackages(*config.apiBasePackages.toTypedArray())
            .scan()
    }
}
