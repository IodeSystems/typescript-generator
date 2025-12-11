package com.iodesystems.ts.extractor

import com.fasterxml.jackson.annotation.JsonInclude
import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.DefaultSpringApiAdapter
import com.iodesystems.ts.adapter.JacksonJsonAdapter
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.adapter.SpringApiAdapter
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.Log.logger
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.*
import io.github.classgraph.ClassInfo
import io.github.classgraph.ScanResult


data class JvmExtractor(
    private val config: Config,
    private val jsonAdapter: JsonAdapter = JacksonJsonAdapter(),
    private val springApiAdapter: SpringApiAdapter = DefaultSpringApiAdapter(config),
) {
    val log = logger()

    fun ClassInfo.tsName(generics: Map<String, TsType.Inline> = emptyMap()): String {
        val name = config.customNaming(name.stripPrefix(packageName))
        if (generics.isEmpty()) return name
        return "$name<${generics.values.joinToString(",") { it.tsName }}>"
    }

    fun buildFromRegistry(
        scan: ScanResult,
        registry: ApiRegistry
    ): Extraction {
        // Root context for registration and walking
        val ctx = RegistrationContext(
            config = config,
            scan = scan,
            jsonAdapter = jsonAdapter,
        )
        val references = ctx.references
        val types = ctx.types

        val apis = registry.apis.mapNotNull { api ->
            val apiCi = scan.getClassInfo(api.controllerFqn)
            if (apiCi == null) {
                log.warn("Can't find controller for $api")
                return@mapNotNull null
            }

            val ciTsName = apiCi.tsName()
            val kmClass = apiCi.kotlinClass()
            val basePath = (api.basePath ?: "").ifBlank { "" }

            // Derive an API-scoped context (for future use)
            val apiCtx = ctx.copy(currentApiBaseName = ciTsName)

            val methods = api.methods.mapNotNull { method ->
                val mi = apiCi.methodInfo.firstOrNull { it.name == method.name }
                if (mi == null) {
                    log.warn("Can't find method $method in $api")
                    return@mapNotNull null
                }
                val kmFun = kmClass?.functions?.firstOrNull { it.name == mi.name }
                val methodFqn = ciTsName + "#" + method.name

                // Derive a method-scoped context to attribute method references
                val methodCtx = apiCtx.copy(currentMethodFqn = methodFqn)

                fun TsType.methodRef(): TsType =
                    methodCtx.addMethodRef(this)

                // Use RegistrationContext.registerType (built-in implementation handles caching and cycles)

                val methodPath = method.path
                val fullPath = when {
                    methodPath.isBlank() && basePath.isBlank() -> "/"
                    methodPath.isBlank() -> basePath
                    basePath.isBlank() -> if (methodPath.startsWith("/")) methodPath else "/$methodPath"
                    else -> {
                        val sep = if (basePath.endsWith("/") || methodPath.startsWith("/")) "" else "/"
                        (basePath + sep + methodPath).replace(Regex("/+"), "/")
                    }
                }

                val req = method.bodyIndex?.let { idx ->
                    val pi = mi.parameterInfo[idx]
                    val si = pi?.typeSignatureOrTypeDescriptor!!
                    val kvp = kmFun?.valueParameters?.getOrNull(idx)
                    val ki = kvp?.type
                    methodCtx.callsiteType(si, ki, kvp, pi.annotationInfo)
                        .methodRef()
                        .inlineReference()
                }
                val rspSi = (mi.typeSignature?.resultType ?: mi.typeSignatureOrTypeDescriptor?.resultType)!!
                val rspKi = kmFun?.returnType

                val rsp = methodCtx.callsiteType(rspSi, rspKi)
                    .methodRef()
                    .inlineReference()

                // Resolve path params via Spring adapter and map to limited primitive union types
                val pathParams = springApiAdapter.resolvePathParams(mi)
                val pathReplacements = pathParams.map { p ->
                    val pi = mi.parameterInfo[p.index]
                    val si = pi.typeSignatureOrTypeDescriptor
                    val kvp = kmFun?.valueParameters?.getOrNull(p.index)
                    val ki = kvp?.type
                    val ts = methodCtx.callsiteType(si, ki, kvp, pi.annotationInfo)
                        .methodRef()
                    val kind = when (ts.tsName) {
                        "number" -> ApiMethod.PathParam.Type.NUMBER
                        else -> ApiMethod.PathParam.Type.STRING
                    }
                    ApiMethod.PathParam(
                        name = p.name,
                        placeholder = p.placeholder,
                        type = kind
                    )
                }

                // Resolve query params via Spring adapter and construct an inline object type
                val queryParamsResolved = springApiAdapter.resolveQueryParams(mi)
                val queryParamsType = if (queryParamsResolved.isNotEmpty()) {
                    val fields: Map<String, TsField> = queryParamsResolved.associate { qp ->
                        val pi = mi.parameterInfo[qp.index]
                        val si = pi.typeSignatureOrTypeDescriptor
                        val kvp = kmFun?.valueParameters?.getOrNull(qp.index)
                        val ki = kvp?.type
                        val t = methodCtx.callsiteType(si, ki, kvp, pi.annotationInfo)
                            .methodRef()
                            .inlineReference()
                        qp.name to TsField(type = t, optional = qp.optional || t.isOptional, nullable = t.isNullable)
                    }
                    // Create a synthetic object type for query
                    val tsName = ciTsName + mi.name.replaceFirstChar { it.uppercaseChar() } + "Query"
                    val qType = TsType.Object(
                        jvmQualifiedClassName = "${apiCi.name}#${mi.name}Query",
                        tsName = tsName,
                        fields = fields,
                    )
                    methodCtx.addType(qType)
                    qType
                } else null

                ApiMethod(
                    name = mi.name,
                    httpMethod = method.http,
                    path = fullPath,
                    requestBodyType = req,
                    responseBodyType = rsp,
                    queryParamsType = queryParamsType,
                    pathReplacements = pathReplacements
                )
            }

            ApiModel(
                tsBaseName = ciTsName,
                jvmQualifiedClassName = apiCi.name,
                basePath = basePath,
                apiMethods = methods
            )
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

