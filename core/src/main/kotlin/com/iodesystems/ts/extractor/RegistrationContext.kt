package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.lib.Log.debug
import com.iodesystems.ts.lib.Log.logger
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import io.github.classgraph.*
import kotlinx.metadata.*
import kotlin.uuid.ExperimentalUuidApi

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
    val types: MutableSet<TsType> = mutableSetOf(),
    val references: MutableSet<TsRef> = mutableSetOf(),
    val fqcnCache: MutableMap<String, TsType> = mutableMapOf(),
    val inProgress: MutableSet<HierarchicalTypeSignature> = mutableSetOf(),
    val nodeByFqcn: MutableMap<String, TsNode> = mutableMapOf(),
    val deps: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val revDeps: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    val todo: ArrayDeque<String> = ArrayDeque(),
    val currentApiBaseName: String? = null,
    val currentMethodFqn: String? = null,
) {
    private val log = logger()
    private val diag = config.diagnosticLogging

    private fun diagnostic() {
        if (!diag || !log.isInfoEnabled) return
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        log.info("[extract] start: todo=${todo.size}, types=${types.size}, refs=${references.size}, usedMemMb=${usedMb}")
    }

    // Resolver entry-point. Processes enqueued JVM classes into TsTypes lazily.
    fun resolveAllRequested() {
        val seen = mutableSetOf<String>()
        var processed = 0
        val startNs = System.nanoTime()
        diagnostic()
        while (true) {
            val fqcn = todo.removeFirstOrNull() ?: break
            if (!seen.add(fqcn)) continue
            // If already materialized as non-inline, skip
            fqcnCache[fqcn]?.let { existing -> if (existing !is TsType.Inline) continue }
            // Skip well-known collection interfaces which are always inlined
            if (TypeShim.isCollectionFqcn(fqcn)) continue
            // Obtain ClassInfo and build the object type
            val typeCi = try {
                scan.getClassInfo(fqcn)
            } catch (_: Throwable) {
                null
            } ?: continue

            val shim = TypeShim.forSignature(typeCi.typeSignatureOrTypeDescriptor)

            // Use current node state if present for flags; default to false
            val node = nodeByFqcn[fqcn]
            val isOptional = (node as? NodeObject)?.isOptional ?: false
            val isNullable = (node as? NodeObject)?.isNullable ?: false

            val built = buildObjectType(
                fqcn = fqcn,
                isOptional = isOptional,
                isNullable = isNullable,
                discriminator = null,
                shim = shim
            )
            addType(built)
            // Any dependencies discovered during build are already enqueued via enqueueNode; loop continues
            processed++
            if (diag && log.isInfoEnabled) {
                val tookMs = (System.nanoTime() - startNs) / 1_000_000
                val rt = Runtime.getRuntime()
                val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
                log.info("[extract] done: processed=${processed}, totalTypes=${types.size}, refs=${references.size}, todoRemaining=${todo.size}, tookMs=${tookMs}, usedMemMb=${usedMb}")
            }
        }
        if (diag && log.isInfoEnabled) {
            val tookMs = (System.nanoTime() - startNs) / 1_000_000
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
            log.info("[extract] done: processed=${processed}, totalTypes=${types.size}, refs=${references.size}, todoRemaining=${todo.size}, tookMs=${tookMs}, usedMemMb=${usedMb}")
        }
    }

    fun hasType(fqcn: String): Boolean = types.any { it.jvmQualifiedClassName == fqcn }
    fun hasType(t: TsType): Boolean = when (t) {
        is TsType.Inline -> false
        else -> (types.any { it.jvmQualifiedClassName == t.jvmQualifiedClassName })
    }

    fun addType(t: TsType) {
        if (!config.includeType(t.jvmQualifiedClassName)) return
        if (hasType(t)) return
        if (types.add(t)) {
            log.info("Registering type: ${t.tsName} (${t.jvmQualifiedClassName})")
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
        val from = currentMethodFqn ?: return toType
        toType.nonGlobalRelatedTypes().forEach { toType ->
            if (references.any { existing -> existing.fromTsBaseName == from && existing.toTsBaseName == toType.tsName && existing.refType == TsRef.Type.METHOD }) return@forEach
            references.add(
                TsRef(
                    fromTsBaseName = from,
                    toTsBaseName = toType.tsName,
                    refType = TsRef.Type.METHOD
                )
            )
        }
        return toType
    }

    fun TsType.typeRef(from: TsType): TsType = addTypeRef(from.tsName, this)
    private fun TypeShim.tsName(
        generics: Map<String, TsType.Inline> = emptyMap(),
        typeSuffix: String = ""
    ): String {
        val name = config.customNaming(this.fqcn().stripPrefix(this.packageName()))
        if (generics.isEmpty()) return name + typeSuffix
        return "$name$typeSuffix<${generics.values.joinToString(",") { it.tsName }}>"
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun inlineRef(
        signature: HierarchicalTypeSignature,
        kmType: KmType? = null,
        kmTypeProjection: KmTypeProjection? = null,
    ): TsType.Inline {
        val kType = kmType ?: kmTypeProjection?.type
        val isNullable = jsonAdapter.isNullable(signature, kType, emptyList()) ?: (kType?.isNullable ?: false)
        quickType(
            s = when (signature) {
                is TypeArgument -> signature.typeSignature ?: signature
                else -> signature
            },
            nullable = isNullable,
            optional = false
        )?.let { return it }

        fun inline(jvmName: String, tsName: String, params: Map<String, TsType.Inline> = emptyMap()): TsType.Inline =
            TsType.Inline(
                jvmQualifiedClassName = jvmName,
                tsName = tsName,
                isNullable = isNullable,
                tsGenericParameters = params
            )

        fun shouldEnqueueGeneric(sig: HierarchicalTypeSignature?): Boolean {
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
        return when (signature) {
            is TypeParameter -> inline(signature.name, signature.name)
            is TypeVariableSignature -> inline(signature.name, signature.name)
            is TypeArgument -> inlineRef(signature.typeSignature, kType)
            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = TypeShim.forSignature(signature)
                val fqcn = shim.fqcn()

                // Collections and Maps as special inline forms with generics
                val inline = when (TypeShim.collectionKindForFqcn(fqcn)) {
                    TypeShim.Companion.CollectionKind.MAP -> {
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
                        if (shouldEnqueueGeneric(kT)) {
                            val core = if (kT is TypeArgument) kT.typeSignature else kT
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                TypeShim.forSignature(core).fqcn()
                            )
                        }
                        if (shouldEnqueueGeneric(vT)) {
                            val core = if (vT is TypeArgument) vT.typeSignature else vT
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                TypeShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    TypeShim.Companion.CollectionKind.SET -> {
                        val arg = shim.getTypeArguments().firstOrNull()
                        val kmArg = kType?.arguments?.getOrNull(0)?.type
                        val inl = inline(
                            jvmName = fqcn,
                            tsName = if (config.setsAsArrays) "Array<T>" else "Set<T>",
                            params = if (arg != null) mapOf("T" to inlineRef(arg, kmArg)) else emptyMap()
                        )
                        if (shouldEnqueueGeneric(arg)) {
                            val core = if (arg is TypeArgument) arg.typeSignature else arg
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                TypeShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    TypeShim.Companion.CollectionKind.LIST -> {
                        val arg = shim.getTypeArguments().firstOrNull()
                        val kmArg = kType?.arguments?.getOrNull(0)?.type
                        val inl = inline(
                            jvmName = fqcn,
                            tsName = "Array<T>",
                            params = if (arg != null) mapOf("T" to inlineRef(arg, kmArg)) else emptyMap()
                        )
                        if (shouldEnqueueGeneric(arg)) {
                            val core = if (arg is TypeArgument) arg.typeSignature else arg
                            if (core is ClassTypeSignature || core is ClassRefTypeSignature) enqueueNode(
                                TypeShim.forSignature(
                                    core
                                ).fqcn()
                            )
                        }
                        inl
                    }

                    else -> null
                }
                if (inline != null) return inline

                // Primitive wrappers mapped directly when no generics
                val direct = TypeShim.directTsForFqcnOrNull(fqcn)
                if (direct != null) return inline(fqcn, direct)

                // Try to obtain ClassInfo if available; ClassGraph may not attach it to ClassRefTypeSignature in some cases
                val ciOrNull = when (signature) {
                    is ClassRefTypeSignature -> signature.classInfo ?: scan.getClassInfo(fqcn)
                    is ClassTypeSignature -> TypeShim.forSignature(signature).getClassInfo()
                    else -> null
                }

                // Prefer direct type arguments; for inner classes, ClassGraph encodes them in suffixTypeArguments
                val args = shim.getTypeArguments().ifEmpty {
                    shim.getSuffixTypeArguments()
                        .lastOrNull()
                        ?: emptyList()
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
                    List(args.size) { idx ->
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
                val tsDisplayName = shim.tsName(displayGenerics)
                enqueueNode(fqcn)
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
        if (!config.includeType(fqcn)) return
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
        if (!todo.contains(fqcn)) {
            todo.addLast(fqcn)
            if (diag && log.isDebugEnabled) {
                log.debug("[extract] enqueue: fqcn=${fqcn}, todo=${todo.size}")
            }
        }
    }

    private fun addDep(fromFqcn: String, toFqcn: String) {
        if (fromFqcn == toFqcn) return
        deps.computeIfAbsent(fromFqcn) { mutableSetOf() }.add(toFqcn)
        revDeps.computeIfAbsent(toFqcn) { mutableSetOf() }.add(fromFqcn)
    }

    private fun buildObjectType(
        fqcn: String,
        shim: TypeShim,
        isOptional: Boolean = false,
        isNullable: Boolean = false,
        discriminator: Pair<String, String>? = null,
        suppressSupertypes: Boolean = false,
        suppressFields: Boolean = false,
        excludeFieldNames: Set<String> = emptySet(),
        preferSuperReplacement: TsType? = null,
    ): TsType.Object {
        val typeCi = shim.getClassInfo()
        val generics = shim.typeParameters().map { param -> inlineRef(param) }.associateBy { it.tsName }
        val superSigs = shim.intersectionSignatures()
        // Render-time supertypes should be light-weight to avoid recursion into union detection.
        // Use inlineRef here and perform any deduplication/rewriting afterwards.
        val superTsRaw = if (suppressSupertypes) emptyList() else superSigs
            .map { sig -> inlineRef(sig) }
            .filter { st -> st.tsName != "any" && config.includeType(st.jvmQualifiedClassName) }
            .map { st ->
                if (preferSuperReplacement != null) {
                    val targetUnionFqcn = "${preferSuperReplacement.jvmQualifiedClassName}#Union"
                    when (st.jvmQualifiedClassName) {
                        preferSuperReplacement.jvmQualifiedClassName,
                        targetUnionFqcn -> preferSuperReplacement

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
            config.includeType(stFqcn) &&
                    // Keep this supertype only if no other direct supertype already includes it transitively
                    !superTsRaw.any { other -> other !== st && allSuperFqcns(other).contains(stFqcn) }
        }

        // Early placeholder to break recursion cycles before walking fields
        val placeholder = TsType.Object(
            jvmQualifiedClassName = fqcn,
            tsName = shim.tsName(generics),
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

        val ctor = jsonAdapter.chooseJsonConstructor(typeCi!!, scan)
        val ctorFields = (ctor?.parameterInfo?.map { f -> jsonAdapter.resolveFieldInfoFromConstructorParameter(f) }
            ?: emptyList())
        val setterFields = typeCi.methodInfo.filter { setter ->
            val n = setter.name
            val singleParam = setter.parameterInfo.size == 1
            val noParam = setter.parameterInfo.size == 0
            if (!setter.isPublic) false
            else if (n.startsWith("get") && n.length > 3 && noParam && n[3].isUpperCase()) true
            else if (n.startsWith("set") && n.length > 3 && singleParam && n[3].isUpperCase()) true
            else n.startsWith("is") && n.length > 2 && n[2].isUpperCase() && noParam
        }.map { setter -> jsonAdapter.resolveFieldInfoFromGetterOrSetter(setter) }

        val classFields = typeCi.fieldInfo.filter { f ->
            if (f.classInfo.name != typeCi.name) false
            else if (f.isStatic || f.isSynthetic || !f.isPublic) false
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

                // If there is a private field, add its annotations:
                typeCi.fieldInfo.filter {
                    it.name == name
                            && it.classInfo.name == typeCi.name
                            && !it.isStatic && !it.isSynthetic && !it.isPublic
                }.firstOrNull()?.let { field ->
                    allAnnotations.addAll(field.annotationInfo)
                }

                val adapterRename = jsonAdapter.resolveRenameFromAnnotations(allAnnotations)
                val fieldType = callsiteType(typeSig!!, null, null, allAnnotations)
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

        if (fields.isNotEmpty() && superTs.isNotEmpty()) {
            // Compute inherited field names directly from JVM supertypes to avoid recursive type registration
            val visited = mutableSetOf<String>()
            fun collectInheritedFieldNames(sh: TypeShim, into: MutableSet<String>) {
                val ci = sh.getClassInfo() ?: return
                val fq = ci.name
                if (!visited.add(fq)) return

                // Constructor-selected fields
                val ctor = jsonAdapter.chooseJsonConstructor(ci, scan)
                val ctorFields = (ctor?.parameterInfo?.map { f ->
                    jsonAdapter.resolveFieldInfoFromConstructorParameter(f)
                } ?: emptyList())

                // Getter/Setter-derived fields (include boolean isX)
                val setterFields = ci.methodInfo.filter { setter ->
                    val n = setter.name
                    val singleParam = setter.parameterInfo.size == 1
                    val noParam = setter.parameterInfo.size == 0
                    if (!setter.isPublic) false
                    else if (n.startsWith("get") && n.length > 3 && noParam && n[3].isUpperCase()) true
                    else if (n.startsWith("set") && n.length > 3 && singleParam && n[3].isUpperCase()) true
                    else n.startsWith("is") && n.length > 2 && n[2].isUpperCase() && noParam
                }.map { setter -> jsonAdapter.resolveFieldInfoFromGetterOrSetter(setter) }

                // Declared instance fields (allow non-public to capture @field: annotations)
                val classFields = ci.fieldInfo.filter { f ->
                    if (f.classInfo.name != ci.name) false
                    else if (f.isStatic || f.isSynthetic || !f.isPublic) false
                    else true
                }.map { f -> jsonAdapter.resolveFiledInfoFromField(f) }

                val names = (setterFields + classFields + ctorFields)
                    .groupBy { it.name }
                    .map { (name, resolved) ->
                        // Apply adapter rename if present across any occurrences
                        val allAnnotations = resolved.flatMap { it.annotations }
                        val adapterRename = jsonAdapter.resolveRenameFromAnnotations(allAnnotations)
                        adapterRename ?: name
                    }
                into.addAll(names)

                // Recurse into their supertypes
                sh.intersectionSignatures().forEach { s ->
                    val next = TypeShim.forSignature(s)
                    collectInheritedFieldNames(next, into)
                }
            }

            val inherited = mutableSetOf<String>()
            superSigs.forEach { sig -> collectInheritedFieldNames(TypeShim.forSignature(sig), inherited) }
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
            fields + (discProp to discField)
        } else fields

        val objectType = TsType.Object(
            jvmQualifiedClassName = fqcn,
            tsName = shim.tsName(generics),
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

                if (!config.includeType(s.type.name)) return null

                TsType.Inline(
                    jvmQualifiedClassName = s.type.name,
                    tsName = tsType,
                    isOptional = optional,
                    isNullable = nullable,
                    tsGenericParameters = emptyMap()
                )
            }

            is ClassTypeSignature, is ClassRefTypeSignature -> {
                val shim = TypeShim.forSignature(s)
                val fqcnEarly = shim.fqcn()
                if (!config.includeType(fqcnEarly)) {
                    return TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = "unknown"
                    )
                }
                config.mappedTypes[fqcnEarly]?.let { mapped ->
                    val generics = shim.typeParameters().associate {
                        it.name to inlineRef(it)
                    }

                    if (hasType(fqcnEarly)) return TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = shim.tsName(generics),
                        tsGenericParameters = generics
                    )
                    val obj = TsType.Object(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = shim.tsName(generics),
                        isOptional = optional,
                        isNullable = nullable,
                        tsGenericParameters = generics,
                        supertypes = listOf(
                            TsType.Inline(
                                jvmQualifiedClassName = "#$mapped",
                                tsName = mapped,
                            )
                        )
                    )
                    addType(obj)
                    return obj.inlineReference()
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
                else {
                    TsType.Inline(
                        jvmQualifiedClassName = fqcnEarly,
                        tsName = direct,
                        isOptional = optional,
                        isNullable = nullable,
                        tsGenericParameters = emptyMap()
                    )
                }
            }

            else -> {
                null
            }
        }
    }

    fun registerType(
        s: HierarchicalTypeSignature,
        discriminator: Pair<String, String>? = null,
    ): TsType {
        val shim = TypeShim.forSignature(s)
        quickType(
            s = s,
            nullable = false,
            optional = false
        )?.let { return it }
        val fqcn = shim.fqcn()
        val createdType: TsType = when (s) {
            is TypeParameter -> TsType.Inline(s.name, s.name)
            is TypeVariableSignature -> TsType.Inline(s.name, s.name)
            is TypeArgument -> inlineRef(s.typeSignature)
            is ClassTypeSignature, is ClassRefTypeSignature, is ArrayTypeSignature -> {
                fqcnCache[fqcn]?.let { existing ->
                    if (existing !is TsType.Inline) return existing
                }
                when (fqcn) {
                    "java.util.Map" -> {
                        val inline = TsType.Inline(
                            jvmQualifiedClassName = fqcn,
                            tsName = "Record<K,V>",
                            tsGenericParameters = mapOf(
                                "K" to TsType.Inline(
                                    jvmQualifiedClassName = "#K",
                                    tsName = "K",
                                ),
                                "V" to TsType.Inline(
                                    jvmQualifiedClassName = "#V",
                                    tsName = "V",
                                )
                            )
                        )
                        return inline
                    }

                    "kotlin.Array",
                    "java.util.Set",
                    "java.util.List" -> {
                        val underlying = if (!config.setsAsArrays && fqcn.endsWith("Set")) "Set"
                        else "Array"
                        val inline = TsType.Inline(
                            jvmQualifiedClassName = fqcn,
                            tsName = "$underlying<T>",
                            tsGenericParameters = mapOf(
                                "T" to TsType.Inline(
                                    jvmQualifiedClassName = "#T",
                                    tsName = "T",
                                )
                            ),
                        )
                        return inline
                    }
                }

                val type: TsType = run {
                    log.debug { "Registering type: $fqcn" }
                    if (shim.isEnum()) {
                        val enumTs = TsType.Enum(
                            jvmQualifiedClassName = fqcn,
                            tsName = shim.tsName(),
                            unionLiteral = jsonAdapter.enumSerializedTypeOrNull(
                                scan,
                                shim.fqcn(),
                                shim.enumConstantNames()
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
                        val resolved = jsonAdapter.resolveDiscriminatedSubTypes(scan, shim)
                        if (resolved != null) {
                            val discriminatorField = resolved.discriminatorProperty
                            val baseGenerics =
                                shim.typeParameters().map { param -> inlineRef(param) }.associateBy { it.tsName }
                            val baseObject = buildObjectType(
                                fqcn = fqcn,
                                shim = shim
                            )
                            enqueueNode(fqcn)
                            addType(baseObject)

                            // Build children as concrete objects with discriminator literals
                            val children: List<TsType> = resolved.options.map { opt ->
                                val childObj = buildObjectType(
                                    fqcn = opt.shim.fqcn(),
                                    discriminator = discriminatorField to opt.discriminatorValue,
                                    preferSuperReplacement = baseObject,
                                    shim = opt.shim
                                )
                                enqueueNode(opt.shim.fqcn())
                                addType(childObj)
                                // Track dependency of base on child
                                addDep(fqcn, opt.shim.fqcn())
                                childObj
                            }

                            // Create a synthesized Union type with '<Base>Union' name under synthetic fqcn
                            val unionFqcn = "$fqcn#Union"
                            val unionTsName = shim.tsName(
                                generics = baseGenerics,
                                typeSuffix = "Union"
                            )
                            val unionType = TsType.Union(
                                jvmQualifiedClassName = unionFqcn,
                                tsName = unionTsName,
                                isOptional = false,
                                isNullable = false,
                                discriminatorField = discriminatorField,
                                children = children,
                                supertypes = listOf(baseObject),
                                tsGenericParameters = baseGenerics
                            )

                            children.forEach {
                                addTypeRef(unionType.tsName, it)
                            }

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
                            discriminator = discriminator,
                            shim = shim
                        )
                        enqueueNode(fqcn)
                        addType(objectType)
                        objectType
                    }
                }

                if (type !is TsType.Enum) {
                    shim.intersectionSignatures()
                        .forEach { sig ->
                            val st = registerType(sig).typeRef(type)
                            // Track deps in node graph
                            addDep(shim.fqcn(), st.jvmQualifiedClassName)
                        }
                }
                if (type !is TsType.Inline) fqcnCache[fqcn] = type
                // Process any newly enqueued work before returning the asked-for type
                resolveAllRequested()
                type
            }

            else -> error("Unhandled HierarchicalTypeSignature: ${s.javaClass.name}")
        }

        when (createdType) {
            is TsType.Inline -> {}
            is TsType.Object,
            is TsType.Union,
            is TsType.Enum -> addType(createdType)
        }
        return createdType
    }

    /**
     * Apply callsite metadata (nullable/optional + constrained generics) to a registered vanilla type.
     */
    fun callsiteType(
        s: HierarchicalTypeSignature,
        k: KmType? = null,
        kvp: KmValueParameter? = null,
        sourceAnnotations: List<AnnotationInfo> = emptyList(),
    ): TsType {
        val isNullable = jsonAdapter.isNullable(s, k, sourceAnnotations) ?: k?.isNullable ?: false
        val isOptional = jsonAdapter.isOptional(k, kvp, sourceAnnotations) ?: kvp?.declaresDefaultValue ?: false

        val shim = TypeShim.forSignature(s)
        // Compute constrained generics from signature/k limits
        val directTypeArgs = shim.getTypeArguments()
        val constrainedGenerics = directTypeArgs.ifEmpty {
            (shim.getSuffixTypeArguments().lastOrNull() ?: emptyList())
        }
        val kTypeArgs = k?.arguments
        val constrainedList: List<TsType.Inline> = constrainedGenerics.mapIndexed { idx, constrained ->
            val km = kTypeArgs?.getOrNull(idx)?.type
            inlineRef(constrained, km)
        }

        // Determine the display generic tokens used in the base type's tsName (e.g., ["T"], ["K","V"], ["A","B"]).
        fun displayGenericTokens(tsName: String): List<String> {
            val lt = tsName.indexOf('<')
            val gt = tsName.lastIndexOf('>')
            if (lt == -1 || gt == -1 || gt <= lt) return emptyList()
            val section = tsName.substring(lt + 1, gt)
            return section.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        val base = quickType(s, isNullable, isOptional) ?: registerType(s)
        val tokens = displayGenericTokens(base.tsName)
        val reifiedGenerics: Map<String, TsType.Inline> = when {
            constrainedList.isNotEmpty() && tokens.size == constrainedList.size ->
                tokens.zip(constrainedList).associate { (token, inl) -> token to inl }

            else -> emptyMap()
        }
        // If it's a quick primitive/alias, just generate inline with flags
        val withGen = if (reifiedGenerics.isNotEmpty()) base.withGenerics(reifiedGenerics) else base
        return withGen.inlineReference(optional = isOptional, nullable = isNullable)
    }
}
