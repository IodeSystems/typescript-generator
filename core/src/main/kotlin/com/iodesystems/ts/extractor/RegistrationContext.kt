package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import io.github.classgraph.*
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.isNullable

/**
 * A composable context that carries extractor state while registering and walking types.
 *
 * This is an initial scaffolding to reduce JvmExtractor complexity without changing behavior.
 * Follow-up steps can migrate more helpers (like registerType) into this type.
 */
data class RegistrationContext(
    val config: Config,
    val scan: ScanResult,
    val jsonAdapter: JsonAdapter,
    // Mutable extraction state
    val types: MutableSet<TsType> = mutableSetOf(),
    val references: MutableSet<TsRef> = mutableSetOf(),
    // Caches for deduplication and cycle handling
    val sigCache: MutableMap<HierarchicalTypeSignature, TsType> = mutableMapOf(),
    val fqcnCache: MutableMap<String, TsType> = mutableMapOf(),
    val inProgress: MutableSet<HierarchicalTypeSignature> = mutableSetOf(),
    // New: post-finalization tasks per FQCN
    val fqcnTodo: MutableMap<String, MutableList<() -> Unit>> = mutableMapOf(),
    // Guard to avoid re-entrant building of discriminated unions
    val polymorphicInProgress: MutableSet<String> = mutableSetOf(),
    // Optional focus while descending
    val currentApiBaseName: String? = null,
    val currentMethodFqn: String? = null,
) {
    fun addType(t: TsType) {
        if (types.add(t)) {
            // Check for alias collisions on tsName
            types.firstOrNull { it != t && it.tsName == t.tsName }?.let { conflict ->
                error(
                    """
                    Type alias name collision: type ${t.tsName}(${t.jvmQualifiedClassName}) conflicts with: ${conflict.tsName}(${conflict.jvmQualifiedClassName}).
                    Consider using renaming of types
                """.trimIndent()
                )
            }
        }
    }

    fun addTypeRef(fromTsBaseName: String, toType: TsType): TsType {
        if (toType is TsType.Inline) return toType
        references.add(
            TsRef(
                fromTsBaseName = fromTsBaseName,
                toTsBaseName = toType.tsName,
                refType = TsRef.Type.TYPE
            )
        )
        return toType
    }

    fun addMethodRef(toType: TsType): TsType {
        if (toType is TsType.Inline) return toType
        val from = currentMethodFqn ?: return toType
        references.add(
            TsRef(
                fromTsBaseName = from,
                toTsBaseName = toType.tsName,
                refType = TsRef.Type.METHOD
            )
        )
        return toType
    }

    // Convenience helpers for call sites
    fun TsType.typeRef(from: TsType): TsType = addTypeRef(from.tsName, this)
    fun TsType.methodRef(): TsType = addMethodRef(this)

    // --- Helpers used by the built-in registerType implementation ---

    private fun ClassInfo.tsName(generics: Map<String, TsType.Inline> = emptyMap()): String {
        val name = config.customNaming(this.name.stripPrefix(this.packageName))
        if (generics.isEmpty()) return name
        return "$name<${generics.values.joinToString(",") { it.tsName }}>"
    }

    fun registerType(
        s: HierarchicalTypeSignature,
        k: KmType? = null,
        kvp: KmValueParameter? = null,
        sourceAnnotations: List<AnnotationInfo> = emptyList(),
        discriminator: Pair<String, String>? = null,
    ): TsType {
        sigCache[s]?.let { return it }

        // Early fast-paths that never participate in recursion: primitives and mapped JVM types
        when (s) {
            is BaseTypeSignature -> {
                val tsType = when (s.typeStr) {
                    "void" -> "void"
                    "boolean" -> "boolean"
                    "char" -> "string"
                    "byte", "short", "int", "long", "float", "double" -> "number"
                    else -> error("Unsupported signature type: $s")
                }
                return TsType.Inline(
                    jvmQualifiedClassName = s.type.name,
                    tsName = tsType,
                    isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue
                    ?: false,
                    isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false,
                    tsGenericParameters = emptyMap()
                )
            }

            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = when (s) {
                    is ClassTypeSignature -> ClassShim.ClassTypeShim(s)
                    is ClassRefTypeSignature -> ClassShim.ClassRefShim(s)
                    else -> error("Invalid signature type: $s")
                }
                val fqcnEarly = shim.fqcn()
                config.mappedTypes[fqcnEarly]?.let { mapped ->
                    return TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = mapped,
                        isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue
                        ?: false,
                        isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false,
                        tsGenericParameters = emptyMap()
                    )
                }
                // Built-in JVM types that map directly and never recurse
                val direct = when (fqcnEarly) {
                    "kotlin.Unit", "java.lang.Void" -> "void"
                    "java.lang.Object" -> "any"
                    "java.math.BigInteger",
                    "java.lang.Character",
                    "kotlin.CharSequence",
                    "java.lang.String" -> "string"


                    "java.lang.Number",
                    "java.lang.Byte",
                    "java.lang.Short",
                    "java.lang.Integer",
                    "java.lang.Long",
                    "java.lang.Float",
                    "java.lang.Double",
                    "java.math.BigDecimal" -> "number"

                    "java.lang.Boolean" -> "boolean"
                    else -> null
                }
                if (direct != null) {
                    return TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = direct,
                        isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue
                        ?: false,
                        isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false,
                        tsGenericParameters = emptyMap()
                    )
                }
            }

            else -> {}
        }

        val isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false
        val isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue ?: false

        fun inline(jvmName: String, tsName: String): TsType.Inline = TsType.Inline(
            jvmQualifiedClassName = jvmName,
            tsName = tsName,
            isOptional = isOptional,
            isNullable = isNullable,
            tsGenericParameters = emptyMap()
        )

        val createdType: TsType = when (s) {
            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = when (s) {
                    is ClassTypeSignature -> ClassShim.ClassTypeShim(s)
                    is ClassRefTypeSignature -> ClassShim.ClassRefShim(s)
                    else -> error("Invalid signature type: $s")
                }
                val fqcn = shim.fqcn()
                val hasDirectArgs = shim.getTypeArguments().isNotEmpty()
                val hasSuffixArgs = shim.getSuffixTypeArguments().lastOrNull()?.isNotEmpty() == true
                val hasKArgs = k?.arguments?.isNotEmpty() == true
                val needsReification = hasDirectArgs || hasSuffixArgs || hasKArgs
                fqcnCache[fqcn]?.let { existing ->
                    if (!needsReification && existing !is TsType.Inline) return existing
                }
                when (fqcn) {
                    "java.util.Map" -> {
                        val ta = shim.getTypeArguments()
                        if (k != null) {
                            val tka = k.arguments
                            val tk = tka[0].type!!
                            val tv = tka[1].type!!
                            return TsType.Inline(
                                jvmQualifiedClassName = fqcn,
                                tsName = "Record<K,V>",
                                isOptional = false,
                                isNullable = k.isNullable,
                                tsGenericParameters = mapOf(
                                    "K" to registerType(ta[0], tk).inlineReference(),
                                    "V" to registerType(ta[1], tv).inlineReference(),
                                )
                            )
                        } else {
                            val tk = registerType(ta[0])
                            val tv = registerType(ta[1])
                            return TsType.Inline(
                                jvmQualifiedClassName = fqcn,
                                tsName = "Record<K,V>",
                                isOptional = false,
                                isNullable = false,
                                tsGenericParameters = mapOf(
                                    "K" to tk.inlineReference(),
                                    "V" to tv.inlineReference(),
                                )
                            )
                        }
                    }

                    "java.util.Set",
                    "java.util.List" -> {
                        val ta = shim.getTypeArguments().first()
                        val tka = if (k != null) k.arguments[0].type else null
                        val t = registerType(ta, tka)
                        val underlying = if (fqcn.endsWith("Set")) "Set"
                        else "Array"
                        return TsType.Inline(
                            jvmQualifiedClassName = fqcn,
                            tsName = "$underlying<T>",
                            isOptional = false,
                            isNullable = k?.isNullable ?: false,
                            tsGenericParameters = mapOf("T" to t.inlineReference()),
                        )
                    }

                    else -> {
                        val typeCi = shim.getClassInfo()
                        val ti = typeCi.typeSignatureOrTypeDescriptor

                        val type: TsType = run {
                            if (typeCi.isEnum) {
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
                                    // Prevent re-entrant resolution of the same polymorphic base
                                    if (!polymorphicInProgress.add(fqcn)) {
                                        // Return an inline reference to the union alias to break cycles
                                        return TsType.Inline(
                                            jvmQualifiedClassName = fqcn,
                                            tsName = typeCi.tsName() + "Union",
                                            isOptional = isOptional,
                                            isNullable = isNullable,
                                            tsGenericParameters = emptyMap()
                                        )
                                    }
                                    // Build base object if it has shape (fields or supertypes)
                                    val tiSig = typeCi.typeSignatureOrTypeDescriptor
                                    val superSigs =
                                        if (tiSig != null) (tiSig.superinterfaceSignatures + tiSig.superclassSignature)
                                            .filterNotNull().filter { !inProgress.contains(it) } else emptyList()
                                    val superTs = superSigs
                                        .map { sig -> registerType(sig, null) }
                                        .filter { st ->
                                            when (st) {
                                                is TsType.Inline -> st.tsName != "any"
                                                else -> true
                                            }
                                        }

                                    // Collect base fields
                                    val ctor = jsonAdapter.chooseJsonConstructor(typeCi, scan)
                                    val ctorFields = (ctor?.parameterInfo?.map { f ->
                                        jsonAdapter.resolveFieldInfoFromConstructorParameter(f)
                                    } ?: emptyList())
                                    val setterFields = typeCi.methodInfo.filter { setter ->
                                        val n = setter.name
                                        val singleParam = setter.parameterInfo.size == 1
                                        val noParam = setter.parameterInfo.size == 0
                                        if (n.startsWith("get") && n.length > 3 && noParam && n[3].isUpperCase()) true
                                        else if (n.startsWith("set") && n.length > 3 && singleParam && n[3].isUpperCase()) true
                                        else n.startsWith("is") && n.length > 2 && n[2].isUpperCase() && noParam
                                    }.map { setter -> jsonAdapter.resolveFieldInfoFromGetterOrSetter(setter) }
                                    val classFields = typeCi.fieldInfo.filter { f ->
                                        if (f.classInfo.name != typeCi.name) false
                                        else if (f.isStatic || f.isSynthetic) false
                                        else true // include non-public instance fields to capture @field: annotations
                                    }.map { f -> jsonAdapter.resolveFiledInfoFromField(f) }

                                    val fields = mutableMapOf<String, TsField>()
                                    (setterFields + classFields + ctorFields).groupBy { it.name }
                                        .forEach { (name, resolvedField) ->
                                            var nullable: Boolean? = null
                                            var optional: Boolean? = null
                                            var rename: String? = null
                                            var typeSig: HierarchicalTypeSignature? = null
                                            val allAnnotations = mutableListOf<AnnotationInfo>()
                                            resolvedField.forEach { r ->
                                                r.nullable?.let { nullable = it }
                                                r.optional?.let { optional = it }
                                                r.rename?.let { rename = it }
                                                typeSig = r.type
                                                allAnnotations += r.annotations
                                            }
                                            // Allow adapter to derive rename from combined annotations (e.g., @JsonProperty/@JsonAlias)
                                            val adapterRename = jsonAdapter.resolveRenameFromAnnotations(allAnnotations)
                                            val fieldType = registerType(typeSig!!)
                                            fields[rename ?: adapterRename ?: name] = TsField(
                                                type = fieldType,
                                                optional = optional ?: false,
                                                nullable = nullable ?: false
                                            )
                                        }
                                    val hasBaseShape = fields.isNotEmpty() || superTs.isNotEmpty()
                                    if (hasBaseShape) {
                                        val baseObject = TsType.Object(
                                            jvmQualifiedClassName = fqcn,
                                            tsName = typeCi.tsName(),
                                            tsGenericParameters = emptyMap(),
                                            isOptional = isOptional,
                                            isNullable = isNullable,
                                            fields = fields,
                                            discriminator = null,
                                            supertypes = superTs
                                        )
                                        superTs.forEach { st -> st.typeRef(baseObject) }
                                        addType(baseObject)
                                    }

                                    // Children
                                    val childrenByOpt = resolved.options.sortedBy { it.classInfo.simpleName }

                                    // Ensure every declared option has a concrete alias; synthesize if missing
                                    childrenByOpt.forEach { opt ->
                                        val ci = opt.classInfo
                                        val alias = ci.tsName()
                                        val exists = types.any { it.tsName == alias }
                                        if (!exists) {
                                            val baseSuper = fqcnCache[fqcn]
                                            val synth = TsType.Object(
                                                jvmQualifiedClassName = ci.name,
                                                tsName = alias,
                                                tsGenericParameters = emptyMap(),
                                                isOptional = false,
                                                isNullable = false,
                                                fields = mapOf(
                                                    resolved.discriminatorProperty to TsField(
                                                        type = TsType.Inline(
                                                            jvmQualifiedClassName = "java.lang.String",
                                                            tsName = "\"${opt.discriminatorValue}\"",
                                                            isOptional = false,
                                                            isNullable = false,
                                                            tsGenericParameters = emptyMap()
                                                        ),
                                                        optional = false,
                                                        nullable = false
                                                    )
                                                ),
                                                discriminator = null,
                                                supertypes = listOfNotNull(baseSuper)
                                            )
                                            addType(synth)
                                        }
                                    }
                                    val unionDisplayChildren: List<TsType> = childrenByOpt.map { opt ->
                                        val ci = opt.classInfo
                                        TsType.Inline(
                                            jvmQualifiedClassName = ci.name,
                                            tsName = ci.tsName(),
                                            isOptional = false,
                                            isNullable = false,
                                            tsGenericParameters = emptyMap()
                                        )
                                    }
                                    val unionType = TsType.Union(
                                        jvmQualifiedClassName = fqcn,
                                        tsName = typeCi.tsName() + "Union",
                                        isOptional = isOptional,
                                        isNullable = isNullable,
                                        discriminatorField = resolved.discriminatorProperty,
                                        children = unionDisplayChildren,
                                        supertypes = emptyList()
                                    )
                                    addType(unionType)
                                    polymorphicInProgress.remove(fqcn)
                                    unionType
                                } else {
                                    // Regular object path
                                    val generics = typeCi.typeSignature?.typeParameters?.let { defined ->
                                        defined.map { param -> registerType(param).inlineReference() }
                                    }?.associateBy { it.tsName } ?: emptyMap()

                                    val tiSig = typeCi.typeSignatureOrTypeDescriptor
                                    val superSigs =
                                        if (tiSig != null) (tiSig.superinterfaceSignatures + tiSig.superclassSignature)
                                            .filterNotNull().filter { !inProgress.contains(it) } else emptyList()
                                    val superTs = superSigs
                                        .map { sig -> registerType(sig, null) }
                                        .filter { st ->
                                            when (st) {
                                                is TsType.Inline -> st.tsName != "any"
                                                else -> true
                                            }
                                        }

                                    // Early placeholder to break recursion cycles before walking fields
                                    val placeholder = TsType.Object(
                                        jvmQualifiedClassName = fqcn,
                                        tsName = typeCi.tsName(generics),
                                        tsGenericParameters = generics,
                                        isOptional = isOptional,
                                        isNullable = isNullable,
                                        fields = emptyMap(),
                                        discriminator = discriminator,
                                        supertypes = emptyList()
                                    )
                                    fqcnCache[fqcn] = placeholder

                                    val ctor = jsonAdapter.chooseJsonConstructor(typeCi, scan)
                                    val ctorFields = (ctor?.parameterInfo?.map { f ->
                                        jsonAdapter.resolveFieldInfoFromConstructorParameter(f)
                                    }
                                        ?: emptyList())
                                    val setterFields = typeCi.methodInfo.filter { setter ->
                                        val n = setter.name
                                        val singleParam = setter.parameterInfo.size == 1
                                        val noParam = setter.parameterInfo.size == 0
                                        if (n.startsWith("get") && n.length > 3 && noParam && n[3].isUpperCase()) true
                                        else if (n.startsWith("set") && n.length > 3 && singleParam && n[3].isUpperCase()) true
                                        else n.startsWith("is") && n.length > 2 && n[2].isUpperCase() && noParam
                                    }.map { setter -> jsonAdapter.resolveFieldInfoFromGetterOrSetter(setter) }
                                    val classFields = typeCi.fieldInfo.filter { f ->
                                        if (f.classInfo.name != typeCi.name) false
                                        else if (f.isStatic || f.isSynthetic) false
                                        else true // include non-public instance fields to capture @field: annotations
                                    }.map { f -> jsonAdapter.resolveFiledInfoFromField(f) }

                                    val fields = (setterFields + classFields + ctorFields)
                                        .groupBy { it.name }
                                        .map { (name, resolved) ->
                                            var nullable: Boolean? = null
                                            var optional: Boolean? = null
                                            var rename: String? = null
                                            var typeSig: HierarchicalTypeSignature? = null
                                            val allAnnotations = mutableListOf<AnnotationInfo>()
                                            resolved.forEach { r ->
                                                r.nullable?.let { nullable = it }
                                                r.optional?.let { optional = it }
                                                r.rename?.let { rename = it }
                                                typeSig = r.type
                                                allAnnotations += r.annotations
                                            }
                                            val adapterRename = jsonAdapter.resolveRenameFromAnnotations(allAnnotations)
                                            val fieldType = registerType(typeSig!!)
                                            Pair(
                                                rename ?: adapterRename ?: name,
                                                TsField(
                                                    type = fieldType,
                                                    optional = optional ?: false,
                                                    nullable = nullable ?: false
                                                )
                                            )
                                        }.toMap()

                                    val inheritedFields: Set<String> = run {
                                        val seen = mutableSetOf<String>()
                                        fun collectFields(t: TsType) {
                                            when (t) {
                                                is TsType.Object -> {
                                                    seen.addAll(t.fields.keys)
                                                    t.supertypes.forEach { collectFields(it) }
                                                }

                                                else -> {}
                                            }
                                        }
                                        superTs.forEach { collectFields(it) }
                                        seen
                                    }
                                    val filteredFields =
                                        if (inheritedFields.isEmpty()) fields else fields.filterKeys { it !in inheritedFields }

                                    val objectType = TsType.Object(
                                        jvmQualifiedClassName = fqcn,
                                        tsName = typeCi.tsName(generics),
                                        tsGenericParameters = generics,
                                        isOptional = isOptional,
                                        isNullable = isNullable,
                                        fields = filteredFields,
                                        discriminator = discriminator,
                                        supertypes = superTs
                                    )
                                    generics.forEach { (_, v) -> v.typeRef(objectType) }
                                    objectType.fields.values.forEach { f ->
                                        types.firstOrNull { t -> f.type.tsName == t.tsName }?.typeRef(objectType)
                                    }
                                    superTs.forEach { st -> st.typeRef(objectType) }

                                    val directTypeArgs = shim.getTypeArguments()
                                    val constrainedGenerics = directTypeArgs.ifEmpty {
                                        (shim.getSuffixTypeArguments().lastOrNull() ?: emptyList())
                                    }
                                    val kTypeArgs = k?.arguments
                                    val typeParams = typeCi.typeSignature?.typeParameters ?: emptyList()
                                    val reifiedGenerics: Map<String, TsType.Inline>? = when {
                                        constrainedGenerics.isNotEmpty() && kTypeArgs != null && typeParams.size == constrainedGenerics.size ->
                                            typeParams.zip(constrainedGenerics.zip(kTypeArgs)).associate { t ->
                                                val generic = t.first
                                                val (constrained, kproj) = t.second
                                                generic.name to registerType(constrained, kproj.type).methodRef()
                                                    .inlineReference()
                                            }

                                        constrainedGenerics.isNotEmpty() ->
                                            typeParams.zip(constrainedGenerics).associate { (generic, constrained) ->
                                                generic.name to registerType(constrained).methodRef().inlineReference()
                                            }

                                        else -> null
                                    }
                                    addType(objectType)
                                    if (reifiedGenerics != null) objectType.typeRef(objectType)
                                        .inlineReference(reifiedGenerics) else objectType
                                }
                            }
                        }

                        if (ti != null && type !is TsType.Enum) {
                            (ti.superinterfaceSignatures + ti.superclassSignature)
                                .filterNotNull()
                                .map { sig -> registerType(sig, null).typeRef(type) }
                        }
                        if (type !is TsType.Inline) fqcnCache[fqcn] = type
                        type
                    }
                }
            }

            is TypeParameter -> inline(s.name, s.name)
            is TypeVariableSignature -> inline(s.name, s.name)
            is TypeArgument -> registerType(s.typeSignature, k).inlineReference()
            else -> TODO()
        }

        when (createdType) {
            is TsType.Inline -> {}
            is TsType.Object, is TsType.Union, is TsType.Enum -> addType(createdType)
        }
        // Signature-level caching remains safe for non-inline results
        if (createdType !is TsType.Inline) sigCache[s] = createdType
        return createdType
    }
}
