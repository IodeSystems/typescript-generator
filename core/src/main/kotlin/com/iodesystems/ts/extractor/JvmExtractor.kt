package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JacksonJsonAdapter
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.Log.logger
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.*
import io.github.classgraph.*
import kotlinx.metadata.*


data class JvmExtractor(
    private val config: Config,
    private val jsonAdapter: JsonAdapter = JacksonJsonAdapter(),
) {
    val log = logger()

    fun ClassInfo.tsName(generics: Map<String, TsType.Inline> = emptyMap()): String {
        val name = config.customNaming(name.stripPrefix(packageName))
        if (generics.isEmpty()) return name
        return "$name<${generics.values.joinToString(",") { it.tsName }}>"
    }

    sealed interface ClassShim {
        fun fqcn(): String
        fun getTypeArguments(): List<TypeArgument>
        fun getClassInfo(): ClassInfo
        fun getSuffixTypeArguments(): List<List<TypeArgument>>

        class ClassTypeShim(sig: ClassTypeSignature) : ClassShim {
            private val classInfo = HackSessor.getClassInfo(sig)!!
            override fun fqcn(): String = classInfo.name
            override fun getTypeArguments(): List<TypeArgument> {
                TODO()
            }

            override fun getClassInfo(): ClassInfo {
                return classInfo
            }

            override fun getSuffixTypeArguments(): List<List<TypeArgument>> {
                return emptyList()
            }

        }

        class ClassRefShim(val sig: ClassRefTypeSignature) : ClassShim {
            override fun fqcn(): String = sig.fullyQualifiedClassName

            override fun getTypeArguments(): List<TypeArgument> {
                return sig.typeArguments
            }

            override fun getClassInfo(): ClassInfo {
                return sig.classInfo
            }

            override fun getSuffixTypeArguments(): List<List<TypeArgument>> {
                return sig.suffixTypeArguments
            }
        }
    }

    fun buildFromRegistry(
        scan: ScanResult,
        registry: ApiRegistry
    ): Extraction {
        val references = mutableSetOf<TsRef>()
        val types = mutableSetOf<TsType>()

        fun addType(type: TsType) {
            if (types.add(type)) {
                // Check for name collisions
                types.firstOrNull {
                    if (it == type) false
                    else it.tsName == type.tsName
                }?.let { conflict ->
                    error(
                        """
                        Type alias name collision: type ${type.tsName}(${type.jvmQualifiedClassName}) conflicts with: ${conflict.tsName}(${type.jvmQualifiedClassName}).
                        Consider using renaming of types
                    """.trimIndent()
                    )
                }
            }
        }

        val cache = mutableMapOf<HierarchicalTypeSignature, TsType>()
        val inProgress = mutableSetOf<HierarchicalTypeSignature>()

        val apis = registry.apis.mapNotNull { api ->
            val apiCi = scan.getClassInfo(api.controllerFqn)
            if (apiCi == null) {
                log.warn("Can't find controller for $api")
                return@mapNotNull null
            }

            val ciTsName = apiCi.tsName()
            val kmClass = apiCi.kotlinClass()
            val basePath = (api.basePath ?: "").ifBlank { "" }

            val methods = api.methods.mapNotNull { method ->
                val mi = apiCi.methodInfo.firstOrNull { it.name == method.name }
                if (mi == null) {
                    log.warn("Can't find method $method in $api")
                    return@mapNotNull null
                }
                val kmFun = kmClass?.functions?.firstOrNull { it.name == mi.name }
                val methodFqn = ciTsName + "#" + method.name
                fun TsType.typeRef(from: TsType): TsType {
                    if (this is TsType.Inline) return this
                    references.add(
                        TsRef(
                            fromTsBaseName = from.tsName,
                            toTsBaseName = this.tsName,
                            refType = TsRef.Type.TYPE
                        )
                    )
                    return this
                }

                fun TsType.methodRef(): TsType {
                    if (this is TsType.Inline) return this
                    references.add(
                        TsRef(
                            fromTsBaseName = methodFqn,
                            toTsBaseName = this.tsName,
                            refType = TsRef.Type.METHOD
                        )
                    )
                    return this
                }

                fun registerType(
                    s: HierarchicalTypeSignature,
                    k: KmType? = null,
                    kvp: KmValueParameter? = null,
                    sourceAnnotations: List<AnnotationInfo> = emptyList(),
                    discriminator: Pair<String, String>? = null,
                ): TsType {
                    val toReturn = cache.getOrPut(s) {
                        if (!inProgress.add(s)) {
                            error("Type is already in progress: $s, please adjust to not call this before it is done")
                        }
                        val isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations)
                            ?: k?.isNullable ?: false
                        val isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations)
                            ?: kvp?.declaresDefaultValue ?: false

                        fun inline(
                            jvmName: String,
                            tsName: String,
                        ): TsType.Inline {
                            return TsType.Inline(
                                jvmQualifiedClassName = jvmName,
                                tsName = tsName,
                                isOptional = isOptional,
                                isNullable = isNullable,
                                tsGenericParameters = emptyMap()
                            )
                        }

                        val createdType: TsType = when (s) {
                            is BaseTypeSignature -> {
                                // This is used when we have a primitive value - these are NON nullable by the JVM
                                val tsType = when (s.typeStr) {
                                    "void" -> "void"
                                    "boolean" -> "boolean"
                                    "char" -> "string"
                                    "byte", "short", "int", "long", "float", "double" -> "number"
                                    else -> {
                                        error("Unsupported signature type: $s")
                                    }
                                }
                                inline(s.type.name, tsType)
                            }

                            is ClassTypeSignature,
                            is ClassRefTypeSignature -> {
                                val shim = when (s) {
                                    is ClassTypeSignature -> ClassShim.ClassTypeShim(s)
                                    is ClassRefTypeSignature -> ClassShim.ClassRefShim(s)
                                    else -> error("Invalid signature type: $s")
                                }
                                val fqcn = shim.fqcn()
                                config.mappedTypes[fqcn]?.let {
                                    inline(fqcn, it)
                                } ?: when (fqcn) {
                                    "kotlin.Unit",
                                    "java.lang.Void" -> inline(fqcn, "void")

                                    "java.lang.Object" -> inline(fqcn, "any")

                                    "java.math.BigInteger",
                                    "java.lang.Character",
                                    "kotlin.CharSequence",
                                    "java.lang.String" -> inline(fqcn, "string")

                                    "java.lang.Number",
                                    "java.lang.Byte",
                                    "java.lang.Short",
                                    "java.lang.Integer",
                                    "java.lang.Float",
                                    "java.lang.Double",
                                    "java.math.BigDecimal" -> inline(fqcn, "number")

                                    "java.lang.Boolean" -> inline(fqcn, "boolean")

                                    "java.util.Map" -> {
                                        val ta = shim.getTypeArguments()
                                        if (k != null) {
                                            val tka = k.arguments
                                            val tk = tka[0].type!!
                                            val tv = tka[1].type!!
                                            TsType.Inline(
                                                jvmQualifiedClassName = fqcn,
                                                tsName = "Record<K,V>",
                                                isOptional = false,
                                                isNullable = k.isNullable,
                                                tsGenericParameters = mapOf(
                                                    "K" to registerType(ta[0].typeSignature, tk)
                                                        .inlineReference(),
                                                    "V" to registerType(ta[1].typeSignature, tv)
                                                        .inlineReference()
                                                ),
                                            )
                                        } else {

                                            val tk = registerType(ta[0].typeSignature)
                                            val tv = registerType(ta[1].typeSignature)
                                            TsType.Inline(
                                                jvmQualifiedClassName = fqcn,
                                                tsName = "Record<K,V>",
                                                isOptional = false,
                                                isNullable = false,
                                                tsGenericParameters = mapOf(
                                                    "K" to tk.inlineReference(),
                                                    "V" to tv.inlineReference()
                                                ),
                                            )
                                        }
                                    }

                                    "java.util.List" -> {
                                        val ta = shim.getTypeArguments().first()
                                        val tka = if (k != null) {
                                            k.arguments[0].type
                                        } else null
                                        val t = registerType(ta.typeSignature, tka)
                                        TsType.Inline(
                                            jvmQualifiedClassName = fqcn,
                                            tsName = "Array<T>",
                                            isOptional = false,
                                            isNullable = k?.isNullable ?: false,
                                            tsGenericParameters = mapOf("T" to t.inlineReference()),
                                        )
                                    }

                                    else -> {
                                        val typeCi = shim.getClassInfo()
                                        val ti = typeCi.typeSignatureOrTypeDescriptor
                                        val type = if (typeCi.isEnum) {
                                            TsType.Enum(
                                                jvmQualifiedClassName = fqcn,
                                                tsName = typeCi.tsName(),
                                                isOptional = false,
                                                isNullable = false,
                                                unionLiteral = jsonAdapter.enumSerializedTypeOrNull(
                                                    typeCi.name,
                                                    typeCi.enumConstants.map { it.name }
                                                ),
                                            )
                                        } else {
                                            val resolved = jsonAdapter.resolveDiscriminatedSubTypes(scan, typeCi)
                                            if (resolved != null) {
                                                TsType.Union(
                                                    jvmQualifiedClassName = fqcn,
                                                    tsName = typeCi.tsName(),
                                                    isOptional = isOptional,
                                                    isNullable = isNullable,
                                                    discriminatorField = resolved.discriminatorProperty,
                                                    children = resolved.options.map {
                                                        registerType(
                                                            it.classInfo.typeSignatureOrTypeDescriptor,
                                                            discriminator = Pair(
                                                                resolved.discriminatorProperty,
                                                                it.discriminatorValue
                                                            )
                                                        )
                                                    }
                                                )
                                            } else {
                                                val ctor = jsonAdapter.chooseJsonConstructor(typeCi, scan)
                                                val ctorFields = (ctor?.parameterInfo?.map { f ->
                                                    jsonAdapter.resolveFieldInfoFromConstructorParameter(f)
                                                } ?: emptyList())
                                                val setterFields = typeCi.methodInfo.filter { setter ->
                                                    val n = setter.name
                                                    val singleParam = setter.parameterInfo.size == 1
                                                    val noParam = setter.parameterInfo.size == 0
                                                    if (n.startsWith("get") && n.length > 3 && noParam && n[3].isUpperCase()) {
                                                        true
                                                    } else if (n.startsWith("set") && n.length > 3 && singleParam && n[3].isUpperCase()) {
                                                        true
                                                    } else if (n.startsWith("is") && n.length > 2
                                                        && n[2].isUpperCase()
                                                        && noParam
                                                    ) {
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }.map { setter ->
                                                    jsonAdapter.resolveFieldInfoFromGetterOrSetter(setter)
                                                }
                                                val classFields = typeCi.fieldInfo.filter { f ->
                                                    if (f.classInfo.name != typeCi.name) false
                                                    else if (f.isStatic || f.isSynthetic) false
                                                    else if (!f.isPublic) false
                                                    else true
                                                }.map { f ->
                                                    jsonAdapter.resolveFiledInfoFromField(f)
                                                }

                                                val fields =
                                                    (setterFields + classFields + ctorFields).groupBy { it.name }
                                                        .map { (name, resolved) ->
                                                            var nullable: Boolean? = null
                                                            var optional: Boolean? = null
                                                            var rename: String? = null
                                                            var type: HierarchicalTypeSignature? = null
                                                            resolved.forEach { r ->
                                                                r.nullable?.let { nullable = it }
                                                                r.optional?.let { optional = it }
                                                                r.rename?.let { rename = it }
                                                                type = r.type
                                                            }

                                                            val fieldType = registerType(type!!)
                                                            Pair(
                                                                rename ?: name, TsField(
                                                                    type = fieldType,
                                                                    optional = optional ?: false,
                                                                    nullable = nullable ?: false
                                                                )
                                                            )
                                                        }.toMap()

                                                val generics = typeCi.typeSignature?.typeParameters?.let { defined ->
                                                    defined.map { param ->
                                                        registerType(param).inlineReference()
                                                    }
                                                }?.associateBy { it.tsName } ?: emptyMap()


                                                val objectType = TsType.Object(
                                                    jvmQualifiedClassName = fqcn,
                                                    tsName = typeCi.tsName(generics),
                                                    tsGenericParameters = generics,
                                                    isOptional = isOptional,
                                                    isNullable = isNullable,
                                                    fields = fields,
                                                    discriminator = discriminator
                                                )
                                                generics.forEach { (_, v) ->
                                                    v.typeRef(objectType)
                                                }
                                                objectType.fields.values.forEach { f ->
                                                    types.firstOrNull { t -> f.type.tsName == t.tsName }
                                                        ?.typeRef(objectType)
                                                }


                                                val constrainedGenerics =
                                                    (shim.getSuffixTypeArguments().lastOrNull() ?: emptyList())
                                                val kTypeArgs = k?.arguments
                                                if (!constrainedGenerics.isEmpty()) {
                                                    val reifiedGenerics =
                                                        if (kTypeArgs != null) typeCi.typeSignature.typeParameters.zip(
                                                            constrainedGenerics.zip(kTypeArgs)
                                                        ).associate { t ->
                                                            val generic = t.first
                                                            val (constrained, ktype) = t.second
                                                            generic.name to registerType(constrained, ktype.type)
                                                                .methodRef()
                                                                .inlineReference()
                                                        }
                                                        else typeCi.typeSignature.typeParameters.zip(
                                                            constrainedGenerics
                                                        ).associate { (generic, constrained) ->
                                                            generic.name to registerType(constrained)
                                                                .methodRef()
                                                                .inlineReference()
                                                        }
                                                    addType(objectType)
                                                    objectType
                                                        .typeRef(objectType)
                                                        .inlineReference(reifiedGenerics)
                                                } else objectType
                                            }
                                        }

                                        if (!typeCi.isEnum && ti != null) (ti.superinterfaceSignatures + ti.superclassSignature)
                                            .filterNotNull()
                                            .filter { !inProgress.contains(it) }
                                            .map { sig ->
                                                registerType(sig, null).typeRef(type)
                                            }
                                        type
                                    }
                                }
                            }

                            is TypeParameter -> {
                                inline(s.name, s.name)
                            }

                            is TypeVariableSignature -> {
                                inline(s.name, s.name)
                            }

                            is TypeArgument -> {
                                registerType(s.typeSignature, k).inlineReference()
                            }

                            else -> {
                                TODO()
                            }
                        }
                        when (createdType) {
                            is TsType.Inline -> {}

                            is TsType.Object,
                            is TsType.Union,
                            is TsType.Enum -> addType(createdType)
                        }
                        inProgress.remove(s)
                        return createdType
                    }
                    return toReturn
                }

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
                    registerType(si, ki, kvp, pi.annotationInfo)
                        .methodRef()
                        .inlineReference()
                }
                val rspSi = (mi.typeSignature?.resultType ?: mi.typeSignatureOrTypeDescriptor?.resultType)!!
                val rspKi = kmFun?.returnType


                val rsp = registerType(rspSi, rspKi)
                    .methodRef()
                    .inlineReference()

                ApiMethod(
                    name = mi.name,
                    httpMethod = method.http,
                    path = fullPath,
                    requestBodyType = req,
                    responseBodyType = rsp,
                    queryParamsType = null,
                    pathTsFields = emptyList()
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
            types = types.toList(),
            typeReferences = references.toList()
        )
    }

    data class Extraction(
        val apis: List<ApiModel>,
        val types: List<TsType>,
        val typeReferences: List<TsRef>,
    )
}

