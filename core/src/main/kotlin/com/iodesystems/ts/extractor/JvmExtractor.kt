package com.iodesystems.ts.extractor

import com.fasterxml.jackson.annotation.JsonInclude
import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.ApiAdapter
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.extractor.extractors.ClassReference
import com.iodesystems.ts.extractor.extractors.JvmMethod
import com.iodesystems.ts.extractor.registry.ApiMethodDescriptor
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.Log.logger
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.*
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import kotlinx.metadata.KmClass


data class JvmExtractor(
    val config: Config,
    val jsonAdapter: JsonAdapter,
    val apiAdapter: ApiAdapter,
    val scan: ScanResult
) {
    val types: MutableSet<TsType> = mutableSetOf()
    val references: MutableSet<TsRef> = mutableSetOf()
    val typeCache: MutableMap<String, TsType> = mutableMapOf()

    val log = logger()

    fun ClassInfo.tsName(generics: Map<String, TsType.Inline> = emptyMap()): String {
        val simpleName = name.stripPrefix(packageName)
        val tsName = config.customNaming(name, simpleName)
        if (generics.isEmpty()) return tsName
        return "$tsName<${generics.values.joinToString(",") { it.name }}>"
    }

    fun forMethod(
        apiMethod: ApiMethodDescriptor,
        methodInfo: MethodInfo,
        apiTsName: String,
        apiKmClass: KmClass?,
    ): JvmMethod {
        return JvmMethod(
            jvmExtractor = this,
            apiMethod = apiMethod,
            fqcn = apiTsName + "#" + methodInfo.name,
            methodInfo = methodInfo,
            apiTsName = apiTsName,
            methodKmFun = apiKmClass?.functions?.firstOrNull { it.name == methodInfo.name }
        )
    }

    fun buildFromRegistry(
        registry: ApiRegistry
    ): Extraction {
        val ctx = RegistrationContext(this)
        val apis = registry.apis.mapNotNull { api ->
            val apiCi = scan.getClassInfo(api.controllerFqn)
            if (apiCi == null) {
                log.warn("Can't find controller for $api")
                return@mapNotNull null
            }
            val apiTsName = apiCi.tsName()
            val apiKmClass = apiCi.kotlinClass()
            val basePath = (api.basePath ?: "").ifBlank { "" }
            val apiCtx = ctx.copy(currentApiBaseName = apiTsName)
            val methods = api.methods.mapNotNull { apiMethod ->
                val methodInfo = apiCi.methodInfo.firstOrNull { it.name == apiMethod.name }
                if (methodInfo == null) {
                    log.warn("Can't find method $apiMethod in $api")
                    return@mapNotNull null
                }
                val methodType = forMethod(
                    apiMethod = apiMethod,
                    methodInfo = methodInfo,
                    apiTsName = apiTsName,
                    apiKmClass = apiKmClass
                )
                val methodCtx = apiCtx.copy(currentMethodFqn = methodType.fqcn)
                val methodPath = apiMethod.path
                val fullPath = when {
                    methodPath.isBlank() && basePath.isBlank() -> "/"
                    methodPath.isBlank() -> basePath
                    basePath.isBlank() -> if (methodPath.startsWith("/")) methodPath else "/$methodPath"
                    else -> {
                        val sep = if (basePath.endsWith("/") || methodPath.startsWith("/")) "" else "/"
                        (basePath + sep + methodPath).replace(Regex("/+"), "/")
                    }
                }
                ApiMethod(
                    name = methodInfo.name,
                    httpMethod = apiMethod.http,
                    path = fullPath,
                    requestBodyType = methodCtx.registerType(methodType.requestType),
                    responseBodyType = methodCtx.registerType(methodType.responseType)
                        ?: TsType.Inline(fqcn = "void", name = "void"),
                    queryParamsType = methodType.queryParametersType?.let { methodCtx.registerType(it) },
                    pathReplacements = methodType.pathParameters
                ).also {
                    it.types().forEach { t -> t?.let { nt -> methodCtx.addMethodRef(nt) } }
                }
            }

            ApiModel(
                tsBaseName = apiTsName,
                jvmQualifiedClassName = apiCi.name,
                basePath = basePath,
                apiMethods = methods
            )
        }

        // Process explicitly included types
        val classRef = ClassReference(config, scan, typeCache, jsonAdapter)
        config.include.forEach { fqcn ->
            try {
                val classInfo = scan.getClassInfo(fqcn)
                if (classInfo == null) {
                    log.warn("Could not find class info for explicitly included type: $fqcn")
                } else {
                    val clazz = classInfo.loadClass()
                    val tsType = classRef.toTsType(clazz)
                    ctx.registerType(tsType)
                }
            } catch (e: Exception) {
                log.warn("Failed to load explicitly included type: $fqcn", e)
            }
        }

        return Extraction(
            apis = apis,
            types = run {
                ctx.resolveAllRequested()
                types.toList()
            },
            typeReferences = references.toList()
        )
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    data class Extraction(
        val apis: List<ApiModel>,
        val types: List<TsType>,
        val typeReferences: List<TsRef>,
    )
}

