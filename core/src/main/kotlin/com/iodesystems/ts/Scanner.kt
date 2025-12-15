package com.iodesystems.ts

import io.github.classgraph.ClassGraph
import io.github.classgraph.ScanResult

class Scanner(private val config: Config) {

    /**
     * Extract package prefixes from patterns for ClassGraph scanning.
     * ClassGraph's acceptPackages needs package names, not class names.
     * - For regex patterns (containing *, +, ?, etc.), extract the literal prefix
     * - For class names (containing $), extract the outer package
     * - For plain packages, use as-is
     */
    private fun extractPackagePrefixes(patterns: List<String>): List<String> {
        return patterns.mapNotNull { pattern ->
            // If it contains regex metacharacters, extract literal prefix before first metachar
            val regexChars = setOf('*', '+', '?', '[', ']', '(', ')', '{', '}', '|', '^', '\\')
            val firstMeta = pattern.indexOfFirst { it in regexChars }

            val basePattern = if (firstMeta >= 0) {
                // Take everything before the first regex metacharacter
                pattern.substring(0, firstMeta)
            } else {
                pattern
            }

            // Now extract package portion from basePattern
            // If it contains $, it's a nested class - take package of outer class
            val dollarIdx = basePattern.indexOf('$')
            val classNameBase = if (dollarIdx >= 0) basePattern.substring(0, dollarIdx) else basePattern

            // Find the package by finding the last dot before an uppercase letter (class name)
            // or just taking everything if it looks like a package
            val lastDot = classNameBase.lastIndexOf('.')
            if (lastDot < 0) {
                // No dots - might be a single segment, skip it
                null
            } else {
                // Check if the segment after the last dot starts with uppercase (class name)
                val afterDot = classNameBase.substring(lastDot + 1)
                if (afterDot.isNotEmpty() && afterDot[0].isUpperCase()) {
                    // This looks like package.ClassName, extract package
                    classNameBase.substring(0, lastDot)
                } else {
                    // This looks like a package path
                    classNameBase.trimEnd('.')
                }
            }
        }.distinct().filter { it.isNotEmpty() }
    }

    fun scan(): ScanResult {
        val packagePrefixes = extractPackagePrefixes(config.packageAccept)
        val rejectPrefixes = extractPackagePrefixes(config.packageReject)

        return ClassGraph()
            .enableAllInfo()
            .enableSystemJarsAndModules()
            .enableExternalClasses()
            .enableInterClassDependencies()
            .let { cg ->
                if (config.classPathUrls.isNotEmpty()) {
                    cg.overrideClasspath(config.classPathUrls)
                } else cg
            }
            .let { cg ->
                if (packagePrefixes.isNotEmpty()) {
                    cg.acceptPackages(*packagePrefixes.toTypedArray())
                } else cg
            }
            .let { cg ->
                if (rejectPrefixes.isNotEmpty()) {
                    cg.rejectPackages(*rejectPrefixes.toTypedArray())
                } else cg
            }
            .scan()
    }
}
