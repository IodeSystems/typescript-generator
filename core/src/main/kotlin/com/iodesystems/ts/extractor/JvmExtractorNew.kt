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


data class JvmExtractorNew(
    private val config: Config,
    private val jsonAdapter: JsonAdapter = JacksonJsonAdapter(),
) {
    val log = logger()

    fun ClassInfo.tsName(generics: Map<String, TsTypeNew.Inline> = emptyMap()): String {
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
    ): ExtractionResultNew {

        val references = mutableSetOf<TsRefNew>()
        val types = mutableSetOf<TsTypeNew>()

        val cache = mutableMapOf<HierarchicalTypeSignature, TsTypeNew>()
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
                fun TsTypeNew.typeRef(from: TsTypeNew): TsTypeNew {
                    if (this is TsTypeNew.Inline) return this
                    references.add(
                        TsRefNew(
                            fromTsBaseName = from.tsName,
                            toTsBaseName = this.tsName,
                            refType = TsRefNew.Type.TYPE
                        )
                    )
                    return this
                }

                fun TsTypeNew.methodRef(): TsTypeNew {
                    if (this is TsTypeNew.Inline) return this
                    references.add(
                        TsRefNew(
                            fromTsBaseName = methodFqn,
                            toTsBaseName = this.tsName,
                            refType = TsRefNew.Type.METHOD
                        )
                    )
                    return this
                }

                fun registerType(
                    s: HierarchicalTypeSignature,
                    k: KmType? = null,
                    kvp: KmValueParameter? = null,
                    sourceAnnotations: List<AnnotationInfo> = emptyList(),
                    discriminator: Pair<String, String>? = null
                ): TsTypeNew {
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
                        ): TsTypeNew.Inline {
                            return TsTypeNew.Inline(
                                jvmQualifiedClassName = jvmName,
                                tsName = tsName,
                                isOptional = isOptional,
                                isNullable = isNullable,
                                tsGenericParameters = emptyMap()
                            )
                        }

                        fun KmType.typeSignature(): HierarchicalTypeSignature {
                            return when (val cls = this.classifier) {
                                is KmClassifier.Class -> {
                                    when (cls.name) {
                                        "kotlin/String" -> scan.getClassInfo("java.lang.String")
                                        else -> TODO()
                                    }
                                }

                                is KmClassifier.TypeAlias -> TODO()
                                is KmClassifier.TypeParameter -> TODO()
                            }.typeSignatureOrTypeDescriptor
                        }

                        val createdType: TsTypeNew = when (s) {
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
                                            TsTypeNew.Inline(
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
                                            TsTypeNew.Inline(
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
                                        TsTypeNew.Inline(
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
                                            inline(
                                                fqcn,
                                                jsonAdapter.enumSerializedTypeOrNull(
                                                    typeCi.name,
                                                    typeCi.enumConstants.map { it.name })
                                            )
                                        } else {
                                            val resolved = jsonAdapter.resolveDiscriminatedSubTypes(scan, typeCi)
                                            if (resolved != null) {
                                                TsTypeNew.Union(
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

                                                val generics = typeCi.typeSignature?.typeParameters?.let { defined ->
                                                    defined.map { param ->
                                                        registerType(param).inlineReference()
                                                    }
                                                }?.associateBy { it.tsName } ?: emptyMap()


                                                val objectType = TsTypeNew.Object(
                                                    jvmQualifiedClassName = fqcn,
                                                    tsName = typeCi.tsName(generics),
                                                    tsGenericParameters = generics,
                                                    isOptional = isOptional,
                                                    isNullable = isNullable,
                                                    fields = (setterFields + classFields + ctorFields).associate { fi ->
                                                        val fieldType = registerType(fi.type, null)
                                                        Pair(
                                                            fi.name,
                                                            TsFieldNew(
                                                                tsName = fieldType.tsName,
                                                                optional = fi.optional ?: false,
                                                                nullable = fi.nullable ?: false
                                                            )
                                                        )
                                                    },
                                                    discriminator = discriminator
                                                )
                                                generics.forEach { (_, v) ->
                                                    v.typeRef(objectType)
                                                }
                                                objectType.fields.values.forEach { f ->
                                                    types.firstOrNull { t -> f.tsName == t.tsName }
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
                                                    types.add(objectType)
                                                    objectType
                                                        .typeRef(objectType)
                                                        .inlineReference(reifiedGenerics)
                                                } else objectType
                                            }
                                        }

                                        if (ti != null) (ti.superinterfaceSignatures + ti.superclassSignature)
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
                            is TsTypeNew.Inline -> {}

                            is TsTypeNew.Object,
                            is TsTypeNew.Union -> types.add(createdType)
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

                ApiMethodNew(
                    name = mi.name,
                    httpMethod = method.http,
                    path = fullPath,
                    requestBodyType = req,
                    responseBodyType = rsp,
                    queryParamsType = null,
                    pathTsFields = emptyList()
                )
            }

            ApiModelNew(
                tsBaseName = ciTsName,
                jvmQualifiedClassName = apiCi.name,
                basePath = basePath,
                apiMethods = methods
            )
        }
        return ExtractionResultNew(
            apis = apis,
            types = types.toList(),
            typeReferences = references.toList()
        )
    }
}