package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import io.github.classgraph.*
import kotlinx.metadata.*
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    val fqcnCache: MutableMap<String, TsType> = mutableMapOf(),
    val inProgress: MutableSet<HierarchicalTypeSignature> = mutableSetOf(),
    // Internal node graph for two-phase resolution
    val nodeByFqcn: MutableMap<String, TsNode> = mutableMapOf(),
    val deps: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val revDeps: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val todo: ArrayDeque<String> = ArrayDeque(),
    val queued: MutableSet<String> = mutableSetOf(),
    // Optional focus while descending
    val currentApiBaseName: String? = null,
    val currentMethodFqn: String? = null,
) {

    // Resolver entry-point. Currently a no-op to keep behavior unchanged until lazy path is flipped on.
    fun resolveAllRequested() {
        // Minimal pass: attempt to process enqueued nodes in a stable order.
        // Current behavior remains eager; nodes mirror already-built TsTypes.
        // This loop is scaffolding for a future Kahn-like resolver.
        val seen = mutableSetOf<String>()
        var progress: Boolean
        var iterations = 0
        do {
            iterations++
            progress = false
            val size = todo.size
            var i = 0
            while (i < size) {
                val fqcn = todo.removeFirstOrNull() ?: break
                // Mark as no longer queued since we've popped it
                queued.remove(fqcn)
                if (!seen.add(fqcn)) {
                    i++
                    continue
                }
                // If dependencies are recorded, ensure they exist in node map; otherwise requeue
                val d = deps[fqcn] ?: emptySet()
                if (d.any { it !in nodeByFqcn.keys }) {
                    // Not ready; requeue for a later pass
                    if (queued.add(fqcn)) todo.addLast(fqcn)
                    i++
                    continue
                }
                // Node considered processed for now
                progress = true
                i++
            }
        } while (progress && iterations < 1000)
    }

    fun addType(t: TsType) {
        // If an identical logical type (same fqcn and tsName) is already present, skip
        if (types.any { it.tsName == t.tsName && it.jvmQualifiedClassName == t.jvmQualifiedClassName }) return
        if (types.add(t)) {
            // Check for alias collisions where the same tsName would refer to a different JVM type
            types.firstOrNull { existing ->
                existing !== t && existing.tsName == t.tsName && existing.jvmQualifiedClassName != t.jvmQualifiedClassName
            }?.let { conflict ->
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
        // Avoid self-references and duplicate entries
        if (fromTsBaseName == toType.tsName) return toType
        if (references.any { it.fromTsBaseName == fromTsBaseName && it.toTsBaseName == toType.tsName && it.refType == TsRef.Type.TYPE }) {
            return toType
        }
        references.add(
            TsRef(
                fromTsBaseName = fromTsBaseName,
                toTsBaseName = toType.tsName,
                refType = TsRef.Type.TYPE
            )
        )
        return toType
    }

    fun <T : TsType> addMethodRef(toType: T): T {
        if (toType is TsType.Inline) return toType
        val from = currentMethodFqn ?: return toType
        // Dedupe method refs, avoid duplicates in the list
        if (references.any { it.fromTsBaseName == from && it.toTsBaseName == toType.tsName && it.refType == TsRef.Type.METHOD }) {
            return toType
        }
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
    fun <T : TsType> T.methodRef(): T = addMethodRef(this)

    // --- Helpers used by the built-in registerType implementation ---

    private fun ClassInfo.tsName(generics: Map<String, TsType.Inline> = emptyMap()): String {
        val name = config.customNaming(this.name.stripPrefix(this.packageName))
        if (generics.isEmpty()) return name
        return "$name<${generics.values.joinToString(",") { it.tsName }}>"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun inlineRef(
        signature: HierarchicalTypeSignature,
        kmType: KmType? = null,
        kmTypeProjection: KmTypeProjection? = null,
    ): TsType.Inline {
        val kType = kmType ?: kmTypeProjection?.type
        val isNullable = jsonAdapter.isNullable(signature, kType, emptyList()) ?: (kType?.isNullable ?: false)

        fun inline(jvmName: String, tsName: String, params: Map<String, TsType.Inline> = emptyMap()): TsType.Inline =
            TsType.Inline(
                jvmQualifiedClassName = jvmName,
                tsName = tsName,
                isOptional = false,
                isNullable = isNullable,
                tsGenericParameters = params
            )

        fun shouldEnqueueGeneric(sig: HierarchicalTypeSignature?, km: KmType?): Boolean {
            if (sig == null) return false
            val core = when (sig) {
                is TypeArgument -> sig.typeSignature
                else -> sig
            }
            // If quickType can produce an inline, then it's primitive/alias and doesn't need enqueue
            return quickType(
                s = core,
                nullable = false,
                optional = false
            ) == null
        }

        // Fast path: attempt quickType for primitives and well-known JVM types
        quickType(
            s = when (signature) {
                is TypeArgument -> signature.typeSignature ?: signature
                else -> signature
            },
            nullable = isNullable,
            optional = false
        )?.let { return it }

        return when (signature) {
            is TypeParameter -> inline(signature.name, signature.name)
            is TypeVariableSignature -> inline(signature.name, signature.name)
            is TypeArgument -> inlineRef(signature.typeSignature, kType)
            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = ClassShim.forSignature(signature)
                val fqcn = shim.fqcn()

                // Collections and Maps as special inline forms with generics
                val inline = when (fqcn) {
                    "kotlin.collections.Map",
                    "java.util.Map" -> {
                        val args = shim.getTypeArguments()
                        val kmArgs = kType?.arguments
                        val kT = args.getOrNull(0)
                        val vT = args.getOrNull(1)
                        val kKm = kmArgs?.getOrNull(0)?.type
                        val vKm = kmArgs?.getOrNull(1)?.type
                        val inl = inline(
                            jvmName = fqcn,
                            tsName = "Record<K,V>",
                            params = mapOf(
                                "K" to inlineRef(kT ?: return inline(fqcn, "Record<unknown,unknown>"), kKm),
                                "V" to inlineRef(vT ?: return inline(fqcn, "Record<unknown,unknown>"), vKm),
                            )
                        )
                        // Enqueue key/value types if they are non-inline class types
                        if (shouldEnqueueGeneric(kT, kKm)) {
                            val core = if (kT is TypeArgument) kT.typeSignature else kT
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                ClassShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        if (shouldEnqueueGeneric(vT, vKm)) {
                            val core = if (vT is TypeArgument) vT.typeSignature else vT
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                ClassShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    "java.util.Set",
                    "kotlin.collections.Set" -> {
                        val arg = shim.getTypeArguments().firstOrNull()
                        val kmArg = kType?.arguments?.getOrNull(0)?.type
                        val inl = inline(
                            jvmName = fqcn,
                            tsName = "Set<T>",
                            params = if (arg != null) mapOf("T" to inlineRef(arg, kmArg)) else emptyMap()
                        )
                        if (shouldEnqueueGeneric(arg, kmArg)) {
                            val core = if (arg is TypeArgument) arg.typeSignature else arg
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                ClassShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    "java.util.List",
                    "kotlin.collections.List" -> {
                        val arg = shim.getTypeArguments().firstOrNull()
                        val kmArg = kType?.arguments?.getOrNull(0)?.type
                        val inl = inline(
                            jvmName = fqcn,
                            tsName = "Array<T>",
                            params = if (arg != null) mapOf("T" to inlineRef(arg, kmArg)) else emptyMap()
                        )
                        if (shouldEnqueueGeneric(arg, kmArg)) {
                            val core = if (arg is TypeArgument) arg.typeSignature else arg
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                ClassShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    else -> {
                        null
                    }
                }
                if (inline != null) return inline

                // Primitive wrappers mapped directly when no generics
                val direct = when (fqcn) {

                    // Strings/characters
                    UUID::class.java.name,
                    Uuid::class.java.name,
                    String::class.java.name,
                    kotlin.String::class.qualifiedName,
                    Character::class.java.name,
                    kotlin.Char::class.qualifiedName -> "string"

                    // Numbers
                    BigDecimal::class.java.name,
                    BigInteger::class.java.name,
                    Short::class.java.name,
                    Integer::class.java.name,
                    Long::class.java.name,
                    Double::class.java.name,
                    Float::class.java.name,
                    Byte::class.java.name,
                    Number::class.java.name,
                    kotlin.Short::class.qualifiedName,
                    kotlin.Int::class.qualifiedName,
                    kotlin.Long::class.qualifiedName,
                    kotlin.Double::class.qualifiedName,
                    kotlin.Float::class.qualifiedName,
                    kotlin.Byte::class.qualifiedName,
                    kotlin.Number::class.qualifiedName -> "number"

                    // Booleans
                    java.lang.Boolean::class.java.name,
                    kotlin.Boolean::class.qualifiedName -> "boolean"

                    else -> null
                }
                if (direct != null) return inline(fqcn, direct)

                // Try to obtain ClassInfo if available; ClassGraph may not attach it to ClassRefTypeSignature in some cases
                val ciOrNull = when (signature) {
                    is ClassRefTypeSignature -> signature.classInfo ?: scan.getClassInfo(fqcn)
                    is ClassTypeSignature -> ClassShim.forSignature(signature).getClassInfo()
                    else -> null
                }

                // Prefer direct type arguments; for inner classes, ClassGraph encodes them in suffixTypeArguments
                val args = shim.getTypeArguments().ifEmpty {
                    shim.getSuffixTypeArguments().lastOrNull() ?: emptyList()
                }
                val kmArgs = kType?.arguments
                val generics = if (args.isNotEmpty()) {
                    args.mapIndexed { idx, sig ->
                        val name = ciOrNull?.typeSignature?.typeParameters?.getOrNull(idx)?.name ?: "T$idx"
                        val kmA = kmArgs?.getOrNull(idx)?.type
                        name to inlineRef(sig, kmA)
                    }.toMap()
                } else emptyMap()

                // For the display name, keep generic parameter symbols (e.g., <T>)
                val displayGenerics = if (args.isNotEmpty()) {
                    args.mapIndexed { idx, _ ->
                        val name = ciOrNull?.typeSignature?.typeParameters?.getOrNull(idx)?.name ?: "T$idx"
                        name to TsType.Inline(
                            jvmQualifiedClassName = name,
                            tsName = name,
                            isOptional = false,
                            isNullable = false,
                            tsGenericParameters = emptyMap()
                        )
                    }.toMap()
                } else emptyMap()

                val tsDisplayName = if (ciOrNull != null) {
                    ciOrNull.tsName(displayGenerics)
                } else {
                    // Fallback: simple name with generic placeholders when ClassInfo is unavailable
                    val simple = fqcn.substringAfterLast('.')
                    if (displayGenerics.isEmpty()) simple else simple + "<" + displayGenerics.keys.joinToString(",") + ">"
                }

                inline(
                    jvmName = fqcn,
                    tsName = tsDisplayName,
                    params = generics
                )
            }

            else -> inline("java.lang.Object", "any")
        }
    }

    private fun enqueueNode(fqcn: String) {
        // Do not enqueue if already fully registered as a non-inline TsType
        fqcnCache[fqcn]?.let { existing -> if (existing !is TsType.Inline) return }
        // Ensure a node exists for this fqcn
        if (!nodeByFqcn.containsKey(fqcn)) {
            nodeByFqcn[fqcn] = NodeObject(
                fqcn = fqcn,
                tsName = fqcn.substringAfterLast('.'),
                isOptional = false,
                isNullable = false
            )
        }
        // Avoid adding duplicates to the work queue
        if (queued.add(fqcn)) todo.addLast(fqcn)
    }

    private fun addDep(fromFqcn: String, toFqcn: String) {
        if (fromFqcn == toFqcn) return
        deps.computeIfAbsent(fromFqcn) { mutableSetOf() }.add(toFqcn)
        revDeps.computeIfAbsent(toFqcn) { mutableSetOf() }.add(fromFqcn)
    }

    private fun buildObjectType(
        fqcn: String,
        typeCi: ClassInfo,
        isOptional: Boolean,
        isNullable: Boolean,
        k: KmType? = null,
        discriminator: Pair<String, String>? = null,
        suppressSupertypes: Boolean = false,
        suppressFields: Boolean = false,
        excludeFieldNames: Set<String> = emptySet(),
        preferSuperReplaceFqcn: String? = null,
        preferSuperReplacement: TsType? = null,
    ): TsType.Object {
        val generics = typeCi.typeSignature?.typeParameters?.let { defined ->
            defined.map { param -> inlineRef(param) }
        }?.associateBy { it.tsName } ?: emptyMap()

        val superSigs =
            (typeCi.typeSignatureOrTypeDescriptor?.let { it.superinterfaceSignatures + it.superclassSignature }
                ?: emptyList())
                .filterNotNull()

        val superTsRaw = if (suppressSupertypes) emptyList() else superSigs
            .map { sig -> registerType(sig, null) }
            .filter { st ->
                when (st) {
                    is TsType.Inline -> st.tsName != "any"
                    else -> true
                }
            }
            .map { st ->
                if (preferSuperReplaceFqcn != null && preferSuperReplacement != null) {
                    val targetUnionFqcn = preferSuperReplaceFqcn + "#Union"
                    when (st.jvmQualifiedClassName) {
                        preferSuperReplaceFqcn, targetUnionFqcn -> preferSuperReplacement
                        else -> st
                    }
                } else st
            }

        fun allSuperFqcns(t: TsType, acc: MutableSet<String> = mutableSetOf()): Set<String> {
            val supers: List<TsType> = when (t) {
                is TsType.Object -> t.supertypes
                is TsType.Union -> t.supertypes
                else -> emptyList()
            }
            supers.forEach { s ->
                if (acc.add(s.jvmQualifiedClassName)) {
                    allSuperFqcns(s, acc)
                }
            }
            return acc
        }

        val superTs = superTsRaw.filter { st ->
            val stFqcn = st.jvmQualifiedClassName
            // Keep this supertype only if no other direct supertype already includes it transitively
            !superTsRaw.any { other -> other !== st && allSuperFqcns(other).contains(stFqcn) }
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
        // Mirror placeholder into node graph immediately
        val node = (nodeByFqcn[fqcn] as? NodeObject) ?: run {
            val created = NodeObject(
                fqcn = fqcn,
                tsName = placeholder.tsName,
                isOptional = placeholder.isOptional,
                isNullable = placeholder.isNullable,
            )
            nodeByFqcn[fqcn] = created
            created
        }
        node.tsName = placeholder.tsName
        node.isOptional = placeholder.isOptional
        node.isNullable = placeholder.isNullable
        node.discriminator = discriminator
        node.generics.clear()
        node.generics.putAll(generics)

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

        var fields = (setterFields + classFields + ctorFields)
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

        if (suppressFields) {
            fields = emptyMap()
        }

        // If a discriminator is specified (for union children), ensure we do not also emit a regular field with the same name
        discriminator?.first?.let { discName ->
            if (fields.containsKey(discName)) {
                fields = fields - discName
            }
        }

        if (excludeFieldNames.isNotEmpty()) {
            fields = fields.filterKeys { it !in excludeFieldNames }
        }

        // Remove fields that are already provided by supertypes
        if (fields.isNotEmpty() && superTs.isNotEmpty()) {
            fun gatherSuperFields(t: TsType, acc: MutableSet<String>) {
                when (t) {
                    is TsType.Object -> {
                        acc.addAll(t.fields.keys)
                        t.supertypes.forEach { gatherSuperFields(it, acc) }
                    }

                    is TsType.Union -> t.supertypes.forEach { gatherSuperFields(it, acc) }
                    else -> {}
                }
            }

            val inherited = mutableSetOf<String>()
            superTs.forEach { gatherSuperFields(it, inherited) }
            if (inherited.isNotEmpty()) {
                fields = fields.filterKeys { it !in inherited }
            }
        }

        // Mirror fields and supertypes into node graph and track dependencies
        node.fields.clear()
        node.fields.putAll(fields)
        node.supertypes.clear()
        node.supertypes.addAll(superTs.map { it.jvmQualifiedClassName })
        superTs.forEach { st ->
            if (st !is TsType.Inline) addDep(fqcn, st.jvmQualifiedClassName)
        }
        fields.values.forEach { f ->
            val ft = f.type
            if (ft !is TsType.Inline) addDep(fqcn, ft.jvmQualifiedClassName)
        }
        // Enqueue dependencies for breadth-first processing
        superTs.forEach { st -> if (st !is TsType.Inline) enqueueNode(st.jvmQualifiedClassName) }
        fields.values.forEach { f ->
            val ft = f.type
            if (ft !is TsType.Inline) enqueueNode(ft.jvmQualifiedClassName)
        }

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
        val filteredFields: Map<String, TsField> =
            if (inheritedFields.isEmpty()) fields else fields.filterKeys { it !in inheritedFields }

        // If discriminator context is provided, inject the discriminator literal field into the object
        val finalFields = if (discriminator != null) {
            val (discProp, discVal) = discriminator
            val discField = TsField(
                type = TsType.Inline(
                    jvmQualifiedClassName = "java.lang.String",
                    tsName = "\"$discVal\"",
                    isOptional = false,
                    isNullable = false,
                    tsGenericParameters = emptyMap()
                ),
                optional = false,
                nullable = false
            )
            filteredFields + (discProp to discField)
        } else filteredFields

        val objectType = TsType.Object(
            jvmQualifiedClassName = fqcn,
            tsName = typeCi.tsName(generics),
            tsGenericParameters = generics,
            isOptional = isOptional,
            isNullable = isNullable,
            fields = finalFields,
            discriminator = discriminator,
            supertypes = superTs
        )

        generics.forEach { (_, v) -> v.typeRef(objectType) }
        objectType.fields.values.forEach { f ->
            types.firstOrNull { t -> f.type.tsName == t.tsName }?.typeRef(objectType)
        }
        superTs.forEach { st -> st.typeRef(objectType) }
        return objectType
    }

    fun quickType(
        s: HierarchicalTypeSignature,
        nullable: Boolean,
        optional: Boolean
    ): TsType.Inline? {
        return when (s) {
            is TypeVariableSignature -> TsType.Inline(
                jvmQualifiedClassName = "#${s.name}",
                tsName = s.name,
                isOptional = optional,
                isNullable = nullable,
                tsGenericParameters = emptyMap()
            )

            is TypeParameter -> TsType.Inline(
                jvmQualifiedClassName = "#${s.name}",
                tsName = s.name,
                isOptional = optional,
                isNullable = nullable,
                tsGenericParameters = emptyMap()
            )

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
                    isOptional = optional,
                    isNullable = nullable,
                    tsGenericParameters = emptyMap()
                )
            }

            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = ClassShim.forSignature(s)
                val fqcnEarly = shim.fqcn()
                config.mappedTypes[fqcnEarly]?.let { mapped ->
                    return TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = mapped,
                        isOptional = optional,
                        isNullable = nullable,
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
                if (direct == null) null
                else return TsType.Inline(
                    jvmQualifiedClassName = fqcnEarly,
                    tsName = direct,
                    isOptional = optional,
                    isNullable = nullable,
                    tsGenericParameters = emptyMap()
                )
            }

            else -> {
                null
            }
        }
    }

    fun registerType(
        s: HierarchicalTypeSignature,
        k: KmType? = null,
        kvp: KmValueParameter? = null,
        sourceAnnotations: List<AnnotationInfo> = emptyList(),
        discriminator: Pair<String, String>? = null,
    ): TsType {
        val isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false
        val isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue ?: false
        quickType(
            s = s,
            nullable = isNullable,
            optional = isOptional
        )?.let { return it }


        fun inline(jvmName: String, tsName: String): TsType.Inline = TsType.Inline(
            jvmQualifiedClassName = jvmName,
            tsName = tsName,
            isOptional = isOptional,
            isNullable = isNullable,
            tsGenericParameters = emptyMap()
        )

        val shim = ClassShim.forSignature(s)

        val createdType: TsType = when (s) {
            is TypeParameter -> inline(s.name, s.name)
            is TypeVariableSignature -> inline(s.name, s.name)
            is TypeArgument -> inlineRef(s.typeSignature, k)
            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val fqcn = shim.fqcn()
                val hasDirectArgs = shim.getTypeArguments().isNotEmpty()
                val hasSuffixArgs = shim.getSuffixTypeArguments().lastOrNull()?.isNotEmpty() == true
                val hasKArgs = k?.arguments?.isNotEmpty() == true
                val needsReification = hasDirectArgs || hasSuffixArgs || hasKArgs
                fqcnCache[fqcn]?.let { existing ->
                    // If we've already materialized a Union for this FQCN, always reuse it to avoid recursion
                    if (existing is TsType.Union) return existing
                    if (!needsReification && existing !is TsType.Inline) return existing
                }
                // Helper to decide if a generic argument should be enqueued (i.e., it is a class type, not an inline primitive/alias)
                fun shouldEnqueueGeneric(sig: HierarchicalTypeSignature?, km: KmType?): Boolean {
                    if (sig == null) return false
                    val core = when (sig) {
                        is TypeArgument -> sig.typeSignature
                        else -> sig
                    }
                    // If quickType can handle it, it's inline (primitive/mapped/Unit/etc) → don't enqueue
                    return quickType(
                        s = core,
                        nullable = false,
                        optional = false
                    ) == null
                }

                when (fqcn) {
                    "java.util.Map" -> {
                        val ta = shim.getTypeArguments()
                        val tka = k?.arguments
                        val inline = TsType.Inline(
                            jvmQualifiedClassName = fqcn,
                            tsName = "Record<K,V>",
                            isOptional = false,
                            isNullable = k?.isNullable ?: false,
                            tsGenericParameters = mapOf(
                                "K" to inlineRef(ta[0], tka?.getOrNull(0)?.type),
                                "V" to inlineRef(ta[1], tka?.getOrNull(1)?.type),
                            )
                        )
                        // Eagerly register K and V object types so they appear in types list
                        ta.getOrNull(0)?.let { kSig ->
                            val core = if (kSig is TypeArgument) kSig.typeSignature else kSig
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                registerType(core, tka?.getOrNull(0)?.type)
                            }
                        }
                        ta.getOrNull(1)?.let { vSig ->
                            val core = if (vSig is TypeArgument) vSig.typeSignature else vSig
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                registerType(core, tka?.getOrNull(1)?.type)
                            }
                        }
                        // Enqueue key/value generic types when they are class types (non-inline)
                        if (shouldEnqueueGeneric(ta.getOrNull(0), tka?.getOrNull(0)?.type)) {
                            val kSig = ta[0]
                            val core = if (kSig is TypeArgument) kSig.typeSignature else kSig
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                enqueueNode(ClassShim.forSignature(core).fqcn())
                            }
                        }
                        if (shouldEnqueueGeneric(ta.getOrNull(1), tka?.getOrNull(1)?.type)) {
                            val vSig = ta[1]
                            val core = if (vSig is TypeArgument) vSig.typeSignature else vSig
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                enqueueNode(ClassShim.forSignature(core).fqcn())
                            }
                        }
                        // Process newly enqueued work in this batch
                        resolveAllRequested()
                        return inline
                    }

                    "java.util.Set",
                    "java.util.List" -> {
                        val ta = shim.getTypeArguments().first()
                        val tka = if (k != null) k.arguments[0].type else null
                        val underlying = if (fqcn.endsWith("Set")) "Set"
                        else "Array"
                        val inline = TsType.Inline(
                            jvmQualifiedClassName = fqcn,
                            tsName = "$underlying<T>",
                            isOptional = false,
                            isNullable = k?.isNullable ?: false,
                            tsGenericParameters = mapOf("T" to inlineRef(ta, tka)),
                        )
                        // Eagerly register the element object type so it appears in types list
                        run {
                            val core = if (ta is TypeArgument) ta.typeSignature else ta
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                registerType(core, tka)
                            }
                        }
                        // Enqueue element type if it's a non-inline class type
                        if (shouldEnqueueGeneric(ta, tka)) {
                            val core = if (ta is TypeArgument) ta.typeSignature else ta
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) {
                                enqueueNode(ClassShim.forSignature(core).fqcn())
                            }
                        }
                        // Process newly enqueued work in this batch
                        resolveAllRequested()
                        return inline
                    }
                }

                val typeCi = shim.getClassInfo()
                val ti = typeCi.typeSignatureOrTypeDescriptor

                val type: TsType = run {
                    if (typeCi.isEnum) {
                        val enumTs = TsType.Enum(
                            jvmQualifiedClassName = fqcn,
                            tsName = typeCi.tsName(),
                            isOptional = false,
                            isNullable = false,
                            unionLiteral = jsonAdapter.enumSerializedTypeOrNull(
                                typeCi.name,
                                typeCi.enumConstants.map { it.name }
                            ),
                        )
                        // Mirror into node graph for resolver bookkeeping
                        nodeByFqcn[fqcn] = NodeEnum(
                            fqcn = fqcn,
                            tsName = enumTs.tsName,
                            isOptional = enumTs.isOptional,
                            isNullable = enumTs.isNullable,
                            unionLiteral = enumTs.unionLiteral,
                        )
                        enumTs
                    } else {
                        val resolved = jsonAdapter.resolveDiscriminatedSubTypes(scan, typeCi)
                        if (resolved != null) {
                            val discriminatorField = resolved.discriminatorProperty
                            val baseGenerics = typeCi.typeSignature?.typeParameters?.let { defined ->
                                defined.map { param -> inlineRef(param) }
                            }?.associateBy { it.tsName } ?: emptyMap()

                            // First, materialize the base object (no discriminator), so it gets emitted separately
                            val baseObject = buildObjectType(
                                fqcn = fqcn,
                                typeCi = typeCi,
                                isOptional = isOptional,
                                isNullable = isNullable,
                                k = k,
                                discriminator = null,
                            )
                            enqueueNode(fqcn)
                            addType(baseObject)

                            // Build children as concrete objects with discriminator literals
                            val children: List<TsType> = resolved.options.map { opt ->
                                val ci = opt.classInfo
                                val childFqcn = ci.name
                                val childObj = buildObjectType(
                                    fqcn = childFqcn,
                                    typeCi = ci,
                                    isOptional = false,
                                    isNullable = false,
                                    k = null,
                                    discriminator = discriminatorField to opt.discriminatorValue,
                                    suppressSupertypes = false,
                                    suppressFields = false,
                                    preferSuperReplaceFqcn = fqcn,
                                    preferSuperReplacement = baseObject,
                                )
                                enqueueNode(childFqcn)
                                addType(childObj)
                                // Track dependency of base on child
                                addDep(fqcn, childFqcn)
                                childObj
                            }

                            // Create a synthesized Union type with '<Base>Union' name under synthetic fqcn
                            val unionFqcn = fqcn + "#Union"
                            val unionTsName = typeCi.tsName(baseGenerics) + "Union"
                            val unionType = TsType.Union(
                                jvmQualifiedClassName = unionFqcn,
                                tsName = unionTsName,
                                isOptional = isOptional,
                                isNullable = isNullable,
                                discriminatorField = discriminatorField,
                                children = children,
                                supertypes = listOf(baseObject),
                                tsGenericParameters = baseGenerics
                            )

                            // Mirror union node
                            val unionNode = (nodeByFqcn[unionFqcn] as? NodeUnion) ?: run {
                                val created = NodeUnion(
                                    fqcn = unionFqcn,
                                    tsName = unionType.tsName,
                                    isOptional = unionType.isOptional,
                                    isNullable = unionType.isNullable,
                                    discriminatorField = discriminatorField,
                                )
                                nodeByFqcn[unionFqcn] = created
                                created
                            }
                            unionNode.tsName = unionType.tsName
                            unionNode.isOptional = unionType.isOptional
                            unionNode.isNullable = unionType.isNullable
                            unionNode.children.clear()
                            unionNode.children.addAll(children.map { it.jvmQualifiedClassName })
                            unionNode.supertypes.clear()
                            unionNode.supertypes.add(baseObject.jvmQualifiedClassName)
                            unionNode.generics.clear()
                            unionNode.generics.putAll(baseGenerics)

                            // Ensure API return types use the union alias by caching base fqcn → union type
                            fqcnCache[fqcn] = unionType
                            fqcnCache[unionFqcn] = unionType
                            enqueueNode(unionFqcn)
                            addType(unionType)

                            // Propagate generics references back to union type (for cross-links)
                            baseGenerics.forEach { (_, v) -> v.typeRef(unionType) }
                            return@run unionType
                        }

                        // Regular object path via reusable builder
                        val objectType = buildObjectType(
                            fqcn = fqcn,
                            typeCi = typeCi,
                            isOptional = isOptional,
                            isNullable = isNullable,
                            k = k,
                            discriminator = discriminator,
                        )
                        enqueueNode(fqcn)
                        addType(objectType)
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
                                    // Do not record method refs for generic parameter reification
                                    generic.name to inlineRef(constrained, kmTypeProjection = kproj)
                                }

                            constrainedGenerics.isNotEmpty() ->
                                typeParams.zip(constrainedGenerics).associate { (generic, constrained) ->
                                    // Do not record method refs for generic parameter reification
                                    generic.name to inlineRef(constrained)
                                }

                            else -> null
                        }
                        if (reifiedGenerics != null) {
                            objectType.typeRef(objectType)
                                .withGenerics(reifiedGenerics)
                        } else objectType
                    }
                }

                if (ti != null && type !is TsType.Enum) {
                    (ti.superinterfaceSignatures + ti.superclassSignature)
                        .filterNotNull()
                        .map { sig ->
                            val st = registerType(sig, null).typeRef(type)
                            // Track deps in node graph
                            addDep(shim.fqcn(), ClassShim.forSignature(sig).fqcn())
                            st
                        }
                }
                if (type !is TsType.Inline) fqcnCache[fqcn] = type
                // Process any newly enqueued work before returning the asked-for type
                resolveAllRequested()
                type
            }

            else -> TODO()
        }

        when (createdType) {
            is TsType.Inline -> {}
            is TsType.Object,
            is TsType.Union,
            is TsType.Enum -> addType(createdType)
        }
        return createdType
    }
}
