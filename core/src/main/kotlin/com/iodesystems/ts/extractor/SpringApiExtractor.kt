package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.AnnotationUtils
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

    companion object {
        private val HTTP_MAPPING_ANNOTATIONS = setOf(
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.DeleteMapping"
        )
    }

    override fun extract(scan: ScanResult): ApiRegistry {
        // Use ClassGraph to find @RestController classes (handles meta-annotations)
        // Then load the class and use Spring's AnnotatedElementUtils for proper @AliasFor resolution
        val controllers: List<Pair<ClassInfo, Class<*>>> = scan.getClassesWithAnnotation(RestController::class.java)
            .filter { ci -> config.includeApi(ci.name) }
            .filter { ci -> !ci.isAnnotation } // Exclude annotation classes (e.g., @ApiController itself)
            .mapNotNull { ci ->
                val clazz = try {
                    ci.loadClass()
                } catch (e: Exception) {
                    log.warn("Failed to load class ${ci.name}: ${e.message}")
                    return@mapNotNull null
                }
                // Check for @RequestMapping using AnnotationUtils (handles @AliasFor and classloader isolation)
                val hasRequestMapping = AnnotationUtils.hasAnnotation(clazz, RequestMapping::class)
                if (hasRequestMapping) ci to clazz else null
            }

        // Warn once if it seems parameter names were not compiled with -parameters
        var warnedMissingParameters = false

        return ApiRegistry.build {
            controllers.forEach { (controllerInfo, clazz) ->
                // Use AnnotationUtils to get merged annotation (handles @AliasFor and classloader isolation)
                val requestMapping = AnnotationUtils.getAnnotation(clazz, RequestMapping::class)
                log.debug("RequestMapping annotation for ${clazz.name}: $requestMapping")
                log.debug("  path: ${requestMapping?.getStringList("path")}")
                log.debug("  value: ${requestMapping?.getStringList("value")}")
                val basePath = requestMapping?.getStringList("path")?.firstOrNull()
                    ?: requestMapping?.getStringList("value")?.firstOrNull()
                    ?: ""
                log.debug("  resolved basePath: '$basePath'")
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
                                val anns = mi.annotationInfo.filter { it.name.endsWith("Mapping") }
                                val withPath = anns.firstOrNull { annotationPath(it).isNotBlank() }
                                annotationPath(withPath ?: anns.firstOrNull())
                            }

                            // Find body parameter index if present
                            val bodyIdx =
                                mi.parameterInfo.indexOfFirst { AnnotationUtils.hasAnnotation(it, RequestBody::class) }
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
                                    if (AnnotationUtils.hasAnnotation(pi, RequestBody::class)) return@forEachIndexed
                                    val rp = AnnotationUtils.getAnnotation(pi, RequestParam::class)
                                    val pv = AnnotationUtils.getAnnotation(pi, PathVariable::class)

                                    if (rp != null || pi.annotationInfo.any { it.name.endsWith("RequestParam") }) {
                                        val rpName = run {
                                            val namePv = rp?.getString("name")
                                            val valuePv = rp?.getString("value")
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
                                        val requiredFlag = rp?.getBoolean("required")
                                        val anns = pi.annotationInfo.map { it.name }
                                        val isOptionalByAnn =
                                            anns.any { it in config.optionalAnnotations || it in config.nullableAnnotations }
                                        val optional = (requiredFlag == false) || isOptionalByAnn

                                        queryParam(idx, rpName, optional)
                                        return@forEachIndexed
                                    }

                                    // Handle @PathVariable - try standard annotation first, then fallback to any *PathVariable
                                    val pvAnn = pv ?: pi.annotationInfo.firstOrNull { it.name.endsWith("PathVariable") }
                                        ?.let { AnnotationUtils.getAnnotation(listOf(it), it.name) }
                                    if (pvAnn != null) {
                                        val annName = pvAnn.getString("name")?.takeIf { it.isNotBlank() }
                                            ?: pvAnn.getString("value")?.takeIf { it.isNotBlank() }
                                        val paramName = pi.name ?: "p$idx"
                                        val placeholder = annName ?: paramName
                                        if (!seenPathNames.add(placeholder)) {
                                            throw IllegalStateException("Duplicate path variable '{$placeholder}' in ${controllerInfo.name}.${mi.name}.")
                                        }
                                        // For path variables, optional isn't typical; accept nullable/optional annotations for completeness
                                        val anns = pi.annotationInfo.map { it.name }
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
        mi.annotationInfo.any { it.name in HTTP_MAPPING_ANNOTATIONS }

    private fun resolveHttpMethod(methodInfo: MethodInfo): TsHttpMethod {
        methodInfo.annotationInfo.forEach { ann ->
            when (ann.name) {
                "org.springframework.web.bind.annotation.GetMapping" -> return TsHttpMethod.GET
                "org.springframework.web.bind.annotation.PostMapping" -> return TsHttpMethod.POST
                "org.springframework.web.bind.annotation.PutMapping" -> return TsHttpMethod.PUT
                "org.springframework.web.bind.annotation.PatchMapping" -> return TsHttpMethod.PATCH
                "org.springframework.web.bind.annotation.DeleteMapping" -> return TsHttpMethod.DELETE
                "org.springframework.web.bind.annotation.RequestMapping" -> {
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

    private fun annotationPath(annotation: AnnotationUtils.AnnotationValues?): String {
        if (annotation == null) return ""
        val v = annotation["value"] ?: annotation["path"] ?: return ""
        return v.asString() ?: ""
    }
}