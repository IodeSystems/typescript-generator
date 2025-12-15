package com.iodesystems.ts.adapter

import com.iodesystems.ts.Config
import com.iodesystems.ts.lib.AnnotationUtils
import io.github.classgraph.MethodInfo
import io.github.classgraph.MethodParameterInfo
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

class SpringApiAdapter(private val config: Config) : ApiAdapter {
    override fun resolvePathParams(method: MethodInfo): List<ApiAdapter.PathParam> {
        val out = mutableListOf<ApiAdapter.PathParam>()
        method.parameterInfo.forEachIndexed { idx, pi ->
            val ann = AnnotationUtils.getAnnotation(pi, PathVariable::class)
                ?: AnnotationUtils.getAnnotation(
                    pi.annotationInfo?.firstOrNull { it.name.endsWith("PathVariable") }?.let { listOf(it) },
                    pi.annotationInfo?.firstOrNull { it.name.endsWith("PathVariable") }?.name ?: ""
                )
            if (ann != null) {
                val annName = ann.getString("name")?.takeIf { it.isNotBlank() }
                    ?: ann.getString("value")?.takeIf { it.isNotBlank() }
                val paramName = pi.name ?: "p$idx"
                val placeholder = annName ?: paramName
                val anns = pi.annotationInfo.map { it.name }
                val isOptionalByAnn = anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                out += ApiAdapter.PathParam(
                    index = idx,
                    name = paramName,
                    placeholder = placeholder,
                    optional = isOptionalByAnn,
                )
            }
        }
        return out
    }

    override fun resolveQueryParams(method: MethodInfo): List<ApiAdapter.QueryParam> {
        val out = mutableListOf<ApiAdapter.QueryParam>()
        method.parameterInfo.forEachIndexed { idx, pi: MethodParameterInfo ->
            val rp = AnnotationUtils.getAnnotation(pi, RequestParam::class)
                ?: AnnotationUtils.getAnnotation(
                    pi.annotationInfo?.firstOrNull { it.name.endsWith("RequestParam") }?.let { listOf(it) },
                    pi.annotationInfo?.firstOrNull { it.name.endsWith("RequestParam") }?.name ?: ""
                )
            if (rp != null) {
                val namePv = rp.getString("name")
                val valuePv = rp.getString("value")
                val rpName = when {
                    !namePv.isNullOrBlank() -> namePv
                    !valuePv.isNullOrBlank() -> valuePv
                    !pi.name.isNullOrBlank() -> pi.name
                    else -> "p$idx"
                }
                val requiredFlag = rp.getBoolean("required")
                val anns = pi.annotationInfo.map { it.name }
                val isOptionalByAnn = anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                val optional = (requiredFlag == false) || isOptionalByAnn
                out += ApiAdapter.QueryParam(idx, rpName!!, optional)
            }
        }
        return out
    }
}