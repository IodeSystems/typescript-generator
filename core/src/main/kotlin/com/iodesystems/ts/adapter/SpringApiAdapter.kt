package com.iodesystems.ts.adapter

import com.iodesystems.ts.Config
import io.github.classgraph.MethodInfo
import io.github.classgraph.MethodParameterInfo
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/**
 * Adapter for framework-specific API surface (Spring MVC).
 * Responsible for interpreting method parameters as path/query, leaving type resolution to JvmExtractor.
 */
interface SpringApiAdapter {
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

class DefaultSpringApiAdapter(private val config: Config) : SpringApiAdapter {
    override fun resolvePathParams(method: MethodInfo): List<SpringApiAdapter.PathParam> {
        val out = mutableListOf<SpringApiAdapter.PathParam>()
        method.parameterInfo.forEachIndexed { idx, pi ->
            val ann = pi.getAnnotationInfo(PathVariable::class.java)
                ?: pi.annotationInfo.firstOrNull { it.classInfo.name.endsWith("PathVariable") }
            if (ann != null) {
                val annName = ann.parameterValues.firstOrNull { it.name == "name" || it.name == "value" }?.value as? String
                val paramName = pi.name ?: "p$idx"
                val placeholder = if (!annName.isNullOrBlank()) annName else paramName
                val anns = pi.annotationInfo.map { it.classInfo.name }
                val isOptionalByAnn = anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                out += SpringApiAdapter.PathParam(
                    index = idx,
                    name = paramName,
                    placeholder = placeholder,
                    optional = isOptionalByAnn,
                )
            }
        }
        return out
    }

    override fun resolveQueryParams(method: MethodInfo): List<SpringApiAdapter.QueryParam> {
        val out = mutableListOf<SpringApiAdapter.QueryParam>()
        method.parameterInfo.forEachIndexed { idx, pi: MethodParameterInfo ->
            val rp = pi.getAnnotationInfo(RequestParam::class.java)
                ?: pi.annotationInfo.firstOrNull { it.classInfo.name.endsWith("RequestParam") }
            if (rp != null) {
                val namePv = rp.parameterValues.firstOrNull { it.name == "name" }?.value as? String
                val valuePv = rp.parameterValues.firstOrNull { it.name == "value" }?.value as? String
                val rpName = when {
                    !namePv.isNullOrBlank() -> namePv
                    !valuePv.isNullOrBlank() -> valuePv
                    !pi.name.isNullOrBlank() -> pi.name
                    else -> "p$idx"
                }
                val requiredFlag = (rp.parameterValues.firstOrNull { it.name == "required" }?.value as? Boolean)
                val anns = pi.annotationInfo.map { it.classInfo.name }
                val isOptionalByAnn = anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                val optional = (requiredFlag == false) || isOptionalByAnn
                out += SpringApiAdapter.QueryParam(idx, rpName!!, optional)
            }
        }
        return out
    }
}
