package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.Log.logger
import com.iodesystems.ts.model.TsHttpMethod
import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import org.springframework.web.bind.annotation.*

class SpringApiExtractor(
    val config: Config
) : ApiExtractor {
    private val log = logger()

    override fun extract(scan: ScanResult): ApiRegistry {
        val controllers: List<ClassInfo> = scan.getClassesWithAnnotation(RestController::class.java)
            .filter { it.getAnnotationInfo(RequestMapping::class.java) != null }
            .filter { ci -> config.includeApi(ci.name) }

        // Warn once if it seems parameter names were not compiled with -parameters
        var warnedMissingParameters = false

        return ApiRegistry.build {
            controllers.forEach { controllerInfo ->
                val basePath = annotationPath(controllerInfo.getAnnotationInfo(RequestMapping::class.java))
                api(controllerInfo.name) {
                    if (basePath.isNotBlank()) basePath(basePath)

                    controllerInfo.methodInfo
                        .filter { mi -> hasHttpMapping(mi) }
                        .forEach { mi ->
                            val http = resolveHttpMethod(mi)
                            // Method-level relative path from annotation
                            // Support @GetMapping/@PostMapping/etc which are meta-annotated with @RequestMapping
                            // Prefer the first mapping annotation that provides a non-blank path/value
                            val methodPath = run {
                                val anns = mi.annotationInfo.filter { it.classInfo.name.endsWith("Mapping") }
                                val withPath = anns.firstOrNull { annotationPath(it).isNotBlank() }
                                annotationPath(withPath ?: anns.firstOrNull())
                            }

                            // Find body parameter index if present
                            val bodyIdx =
                                mi.parameterInfo.indexOfFirst { it.getAnnotationInfo(RequestBody::class.java) != null }
                            // New rule: Body is allowed for non-GET methods, but MUST NOT be present for GET
                            if ((http == TsHttpMethod.GET) && bodyIdx != -1) {
                                throw IllegalStateException("HTTP $http method '${controllerInfo.name}.${mi.name}' must not declare a  @RequestBody.")
                            }

                            method(mi.name) {
                                http(http)
                                path(methodPath)
                                if (bodyIdx >= 0) body(bodyIdx)

                                log.info("ApiMethod found: ${controllerInfo.name}#${mi.name}")
                                // Map @RequestParam to Query params, and @PathVariable to Path params
                                val seenQueryNames = HashSet<String>()
                                val seenPathNames = HashSet<String>()
                                mi.parameterInfo.forEachIndexed { idx, pi ->
                                    if (pi.getAnnotationInfo(RequestBody::class.java) != null) return@forEachIndexed
                                    val rp = pi.getAnnotationInfo(RequestParam::class.java)
                                    val pv = pi.getAnnotationInfo(PathVariable::class.java)

                                    if (rp != null || pi.annotationInfo.any { it.classInfo.name.endsWith("RequestParam") }) {
                                        val rpName = run {
                                            val namePv =
                                                rp?.parameterValues?.firstOrNull { it.name == "name" }?.value as? String
                                            val valuePv =
                                                rp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
                                            when {
                                                !namePv.isNullOrBlank() -> namePv
                                                !valuePv.isNullOrBlank() -> valuePv
                                                !pi.name.isNullOrBlank() -> pi.name
                                                else -> "p$idx"
                                            }
                                        }

                                        if (!warnedMissingParameters && (pi.name.isNullOrBlank() || pi.name!!.matches(
                                                Regex("(arg|p)\\d+")
                                            ))
                                        ) {
                                            log.warn("ApiMethod parameter names appear to be missing. Compile sources with -parameters to retain names for better query param generation.")
                                            warnedMissingParameters = true
                                        }

                                        if (!seenQueryNames.add(rpName)) {
                                            throw IllegalStateException("Duplicate query parameter name '$rpName' in ${controllerInfo.name}.${mi.name}. Please rename parameters or use @RequestParam(name=...) to disambiguate.")
                                        }

                                        // Optional if RequestParam.required=false or annotated as optional/nullable per Config
                                        val requiredFlag =
                                            (rp?.parameterValues?.firstOrNull { it.name == "required" }?.value as? Boolean)
                                        val anns = pi.annotationInfo.map { it.classInfo.name }
                                        val isOptionalByAnn =
                                            anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                                        val optional = (requiredFlag == false) || isOptionalByAnn

                                        queryParam(idx, rpName, optional)
                                        return@forEachIndexed
                                    }

                                    if (pv != null || pi.annotationInfo.any { it.classInfo.name.endsWith("PathVariable") }) {
                                        val ann =
                                            pv ?: pi.annotationInfo.first { it.classInfo.name.endsWith("PathVariable") }
                                        val annName =
                                            ann.parameterValues.firstOrNull { it.name == "name" || it.name == "value" }?.value as? String
                                        val paramName = pi.name ?: "p$idx"
                                        val placeholder = if (!annName.isNullOrBlank()) annName else paramName
                                        if (!seenPathNames.add(placeholder)) {
                                            throw IllegalStateException("Duplicate path variable '{$placeholder}' in ${controllerInfo.name}.${mi.name}.")
                                        }
                                        // For path variables, optional isn't typical; accept nullable/optional annotations for completeness
                                        val anns = pi.annotationInfo.map { it.classInfo.name }
                                        val isOptionalByAnn =
                                            anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                                        pathParam(idx, placeholder, paramName, isOptionalByAnn)
                                        return@forEachIndexed
                                    }
                                }
                            }
                        }
                }
            }
        }
    }

    private fun hasHttpMapping(mi: MethodInfo): Boolean =
        mi.hasAnnotation(RequestMapping::class.java)

    private fun resolveHttpMethod(methodInfo: MethodInfo): TsHttpMethod {
        methodInfo.annotationInfo.forEach { ann ->
            when (ann.classInfo.name) {
                RequestMapping::class.java.name -> {
                    val pv = ann.parameterValues.firstOrNull { it.name == "method" }?.value
                    val methods: List<*> = when (pv) {
                        is Array<*> -> pv.toList()
                        null -> emptyList<Any>()
                        else -> listOf(pv)
                    }
                    if (methods.isEmpty()) return TsHttpMethod.GET
                    val first = methods.first().toString().substringAfterLast('.')
                    return when (first) {
                        "GET" -> TsHttpMethod.GET
                        "POST" -> TsHttpMethod.POST
                        "PUT" -> TsHttpMethod.PUT
                        "PATCH" -> TsHttpMethod.PATCH
                        "DELETE" -> TsHttpMethod.DELETE
                        else -> TsHttpMethod.GET
                    }
                }
            }
        }
        return TsHttpMethod.GET
    }

    // The method-level path is extracted via annotationPath; base path is carried in the registry

    private fun annotationPath(annotation: AnnotationInfo?): String {
        if (annotation == null) return ""
        return annotation.parameterValues.firstOrNull { it.name == "value" || it.name == "path" }?.let {
            when (val v = it.value) {
                is Array<*> -> v.firstOrNull()?.toString() ?: ""
                else -> v?.toString() ?: ""
            }
        } ?: ""
    }
}