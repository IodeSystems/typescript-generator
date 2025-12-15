package com.iodesystems.ts.adapter

import io.github.classgraph.MethodInfo

/**
 * Adapter for framework-specific API surface (Spring MVC).
 * Responsible for interpreting method parameters as path/query, leaving type resolution to JvmExtractor.
 */
interface ApiAdapter {
    data class PathParam(
        val index: Int,
        val name: String,        // java/kotlin parameter name (used as field name)
        val placeholder: String, // placeholder inside the path template
        val optional: Boolean,
    )

    data class QueryParam(
        val index: Int,
        val name: String,        // flattened key (may contain dots per Spring)
        val optional: Boolean,
    )

    fun resolvePathParams(method: MethodInfo): List<PathParam>
    fun resolveQueryParams(method: MethodInfo): List<QueryParam>
}

