package com.iodesystems.ts.extractor

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JacksonJsonAdapter
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.adapter.TsFieldInspection
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinMetadata
import com.iodesystems.ts.extractor.registry.ApiRegistry
import com.iodesystems.ts.lib.Strings.stripPrefix
import com.iodesystems.ts.model.ExtractionResult
import com.iodesystems.ts.model.TsBody
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsRef.ByType
import com.iodesystems.ts.model.TsType
import io.github.classgraph.*
import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import kotlinx.metadata.declaresDefaultValue
import java.lang.Byte
import java.lang.Double
import java.lang.Float
import java.lang.Long
import java.lang.Short
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.Boolean
import kotlin.CharSequence
import kotlin.IllegalStateException
import kotlin.Pair
import kotlin.String
import kotlin.Throwable
import kotlin.let
import kotlin.run
import kotlin.to

open class JvmExtractor(
    protected val config: Config,
    protected val jsonAdapter: JsonAdapter = JacksonJsonAdapter(),
) {
    // Track TypeScript alias names across a single extraction to detect custom naming collisions
    protected var aliasToJvm: LinkedHashMap<String, String> = LinkedHashMap()

    protected fun ensureAliasUnique(tsAlias: String, jvmFqn: String) {
        val other = aliasToJvm[tsAlias]
        if (other == null) {
            aliasToJvm[tsAlias] = jvmFqn
            return
        }
        if (other != jvmFqn) {
            throw IllegalStateException(
                "Type alias name collision detected: custom naming resolved both '$other' and '$jvmFqn' to '$tsAlias'. " +
                        "Please adjust Config.customNaming or mappedTypes to disambiguate."
            )
        }
    }

    protected fun primitive(ts: String): TsType = TsType(ts, ts, TsBody.PrimitiveBody(ts))
    protected fun union(a: TsType, b: TsType): TsType =
        TsType("union", "${renderTs(a)} | ${renderTs(b)}", TsBody.UnionBody(listOf(a, b)))

    protected fun renderTs(t: TsType): String = t.typeScriptTypeName

    // Simple per-extractor cache to avoid recomputing the exact same TypeSignature → TsType
    private val tsCache = HashMap<String, TsType>()

    // Unified entry point for resolving any TypeSignature to a TsType, with caching and registration
    protected fun ensureType(
        scan: ScanResult,
        controller: ClassInfo?,
        sig: TypeSignature?,
        allTypes: MutableMap<String, TsType>,
        isReturn: Boolean = false
    ): TsType {
        val key = (sig?.toString() ?: "<null>") + "|return=" + isReturn
        tsCache[key]?.let { return it }
        val t = toType(scan, controller, sig, allTypes, isReturn)
        tsCache[key] = t
        return t
    }

    // Convert a parameter/field signature into a TsType (for request bodies)
    protected fun toType(
        scan: ScanResult,
        controller: ClassInfo?,
        sig: TypeSignature?,
        allTypes: MutableMap<String, TsType>,
        isReturn: Boolean = false
    ): TsType {
        sig ?: return primitive("any")
        return when (sig) {
            is BaseTypeSignature -> when (sig.typeStr) {
                "void" -> primitive("void")
                "boolean" -> primitive("boolean")
                "char" -> primitive("string")
                "byte", "short", "int", "long", "float", "double" -> primitive("number")
                else -> primitive("any")
            }

            is ArrayTypeSignature -> {
                val elem = ensureType(scan, controller, sig.elementTypeSignature, allTypes)
                TsType("kotlin.Array", "${renderTs(elem)}[]", TsBody.ArrayBody(elem))
            }

            is ClassRefTypeSignature -> classRefToType(scan, controller, sig, allTypes, isReturn)
            is TypeVariableSignature -> primitive(sig.name)
            else -> primitive("any")
        }
    }

    protected fun classRefToType(
        scan: ScanResult,
        controller: ClassInfo?,
        sig: ClassRefTypeSignature,
        allTypes: MutableMap<String, TsType>,
        isReturn: Boolean = false
    ): TsType {
        val fqn = sig.fullyQualifiedClassName
        config.mappedTypes[fqn]?.let { return primitive(it) }
        return when (fqn) {
            // Map Unit/Void to void for return types
            "kotlin.Unit" -> if (isReturn) primitive("void") else primitive("any")
            Void::class.java.name -> primitive("void")
            java.lang.Boolean::class.java.name -> if (isReturn) union(
                primitive("boolean"),
                primitive("null")
            ) else primitive("boolean")

            Byte::class.java.name,
            Short::class.java.name,
            Integer::class.java.name,
            Long::class.java.name,
            Float::class.java.name,
            Double::class.java.name -> primitive("number")

            BigDecimal::class.java.name -> primitive("number")
            BigInteger::class.java.name -> primitive("string")

            Character::class.java.name,
            java.lang.String::class.java.name,
            CharSequence::class.java.name -> primitive("string")

            else -> {
                if (isReturn && fqn == java.lang.Boolean::class.java.name) return primitive("boolean")

                // Mapped generics for collections and maps
                val targs = sig.typeArguments ?: emptyList()
                when (fqn) {
                    "java.util.List", "kotlin.collections.List", "java.util.Set", "kotlin.collections.Set", "java.util.Collection", "kotlin.collections.Collection" -> {
                        val elemSig = targs.firstOrNull()?.typeSignature
                        val elem =
                            ensureType(scan, controller ?: scan.getClassInfo("java.lang.Object"), elemSig, allTypes)
                        return TsType(fqn, "${renderTs(elem)}[]", TsBody.ArrayBody(elem))
                    }

                    "java.util.Map", "kotlin.collections.Map" -> {
                        val valSig = if (targs.size >= 2) targs[1].typeSignature else null
                        val vType =
                            ensureType(scan, controller ?: scan.getClassInfo("java.lang.Object"), valSig, allTypes)
                        return TsType(
                            fqn,
                            "Record<string, ${renderTs(vType)}>",
                            TsBody.PrimitiveBody("Record<string, ${renderTs(vType)}>")
                        )
                    }
                }

                // Complex type: object/record or polymorphic union (response or request)
                val ci = scan.getClassInfo(fqn) ?: return primitive(fqn.substringAfterLast('.'))
                // Enums serialize to a union of string literal names (adapter may customize)
                if (ci.isEnum) {
                    val enumTs = jsonAdapter.enumSerializedTypeOrNull(ci.name, ci.enumConstants.map { it.name })
                    return primitive(enumTs)
                }
                // Parameterized class instantiation handling: return an instantiated reference
                val typeArgs = sig.typeArguments ?: emptyList()
                if (typeArgs.isNotEmpty()) {
                    // Ensure the base complex type (generic alias) exists
                    ensureComplexType(scan, ci, allTypes)
                    val baseAlias = config.customNaming(ci.name.stripPrefix(ci.packageName + "."))
                    val renderedArgs = typeArgs.map { ta ->
                        val at = toTypeWithBindings(scan, controller, ta.typeSignature, allTypes, isReturn)
                        renderTs(at)
                    }
                    val tsName = baseAlias + "<" + renderedArgs.joinToString(", ") + ">"
                    return TsType(ci.name, tsName, TsBody.PrimitiveBody(tsName))
                }
                // Polymorphic unions via adapter-provided discriminated subtypes
                // (Applicable for both returns and inputs if configured)
                val resolved = jsonAdapter.resolveDiscriminatedSubTypes(scan, ci)
                if (resolved != null) {
                    val discriminator = resolved.discriminatorProperty
                    val options = resolved.options.map { opt ->
                        val impl = opt.classInfo
                        val discValue = opt.discriminatorValue
                        val fields = extractFields(scan, impl, allTypes)
                        val optionalParams = kotlinOptionalConstructorParams(impl)
                        val optionTsFields = mutableListOf<TsField>()
                        optionTsFields += TsField(
                            discriminator,
                            primitive("\"$discValue\""),
                            optional = false,
                            nullable = false
                        )
                        val restTsFields = fields.map { (name, flags) ->
                            val tsType = if (flags.nullable) union(flags.ts, primitive("null")) else flags.ts
                            TsField(name, tsType, optional = (name in optionalParams), nullable = flags.nullable)
                        }
                        optionTsFields.addAll(restTsFields)

                        // Collect implemented interfaces (excluding the polymorphic base itself)
                        val ifaceAliases = impl.interfaces.mapNotNull { ifaceRef ->
                            val ifaceCi = scan.getClassInfo(ifaceRef.name)
                            if (ifaceCi == null || ifaceCi.name == ci.name) return@mapNotNull null
                            interfaceAliasAndRecord(scan, ifaceCi, impl.name, allTypes)
                        }
                        val alias = config.customNaming(
                            impl.name.stripPrefix(impl.packageName + ".")
                        )
                        ensureAliasUnique(alias, impl.name)
                        TsType(
                            impl.name,
                            alias,
                            TsBody.ObjectBody(optionTsFields),
                            intersects = ifaceAliases,
                        )
                    }
                    // Register option types so they are emitted as separate definitions
                    options.forEach { opt ->
                        val existing = allTypes[opt.jvmQualifiedClassName]
                        if (existing == null) {
                            allTypes[opt.jvmQualifiedClassName] = opt
                        } else if (existing.body is TsBody.ObjectBody) {
                            // Merge tsFields if already present
                            val newFields = (opt.body as TsBody.ObjectBody).tsFields
                            allTypes[opt.jvmQualifiedClassName] = existing.copy(body = TsBody.ObjectBody(newFields))
                        }
                    }
                    val alias = config.customNaming(ci.name.stripPrefix(ci.packageName + "."))
                    ensureAliasUnique(alias, ci.name)
                    return TsType(
                        ci.name,
                        alias,
                        TsBody.UnionBody(options),
                        references = emptyList(),
                        intersects = emptyList(),
                    )
                }

                // Regular object type: build and register via ensureComplexType, then return the registered type
                ensureComplexType(scan, ci, allTypes)
                return allTypes[ci.name] ?: primitive(ci.simpleName)
            }
        }
    }

    // Resolve a signature with support for bound generic type variables
    protected fun toTypeWithBindings(
        scan: ScanResult,
        controller: ClassInfo?,
        sig: TypeSignature?,
        allTypes: MutableMap<String, TsType>,
        isReturn: Boolean = false,
        bindings: Map<String, TsType> = emptyMap(),
    ): TsType {
        sig ?: return primitive("any")
        return when (sig) {
            is BaseTypeSignature -> toType(scan, controller, sig, allTypes, isReturn)
            is ArrayTypeSignature -> {
                val elem = toTypeWithBindings(scan, controller, sig.elementTypeSignature, allTypes, isReturn, bindings)
                TsType("kotlin.Array", "${renderTs(elem)}[]", TsBody.ArrayBody(elem))
            }

            is ClassRefTypeSignature -> classRefToType(scan, controller, sig, allTypes, isReturn)
            is TypeVariableSignature -> {
                // Use bound concrete type if available; otherwise render as the type variable name itself (e.g., T)
                bindings[sig.name] ?: primitive(sig.name)
            }

            else -> primitive("any")
        }
    }

    protected data class FieldFlags(
        val ts: TsType,
        val nullable: Boolean,
        val optionalByAnnotation: Boolean,
    )

    protected fun extractFields(
        scan: ScanResult,
        classInfo: ClassInfo,
        allTypes: MutableMap<String, TsType>
    ): List<Pair<String, FieldFlags>> {
        val fields = linkedMapOf<String, FieldFlags>()
        // Collect Kotlin primary-constructor parameter annotations (e.g., @param:JsonProperty)
        val ctorParamAnnsByName = constructorParamAnnotationsByName(scan, classInfo)
        val ctorParamNames: Set<String> = ctorParamAnnsByName.keys
        // Collect Kotlin-declared property names for this class (to avoid picking up interface default getters)
        val kotlinPropertyNames: Set<String> = try {
            classInfo.kotlinMetadata()?.kotlinClass()?.properties?.map { it.name }?.toSet() ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
        val isInterface = classInfo.isInterface
        // Build a set of getter-derived property names declared on super types (classes or interfaces)
        val superGetterPropNames: Set<String> = try {
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque<ClassInfo>()
            // Enqueue direct superclass (if any) and direct interfaces
            classInfo.superclasses.forEach { sc ->
                scan.getClassInfo(sc.name)?.let { queue.add(it) }
            }
            classInfo.interfaces.forEach { iface ->
                scan.getClassInfo(iface.name)?.let { queue.add(it) }
            }
            val names = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val ci = queue.removeFirst()
                if (!visited.add(ci.name)) continue
                // Collect zero-arg getters on this super type
                ci.methodInfo
                    .filter { mi -> !mi.isStatic && !mi.isSynthetic && mi.isPublic && mi.parameterInfo.isEmpty() }
                    .mapNotNull { mi ->
                        val n = mi.name
                        when {
                            n.startsWith("get") && n.length > 3 -> n.substring(3).replaceFirstChar { it.lowercase() }
                            n.startsWith("is") && n.length > 2 -> n.substring(2).replaceFirstChar { it.lowercase() }
                            else -> null
                        }
                    }
                    .forEach { names.add(it) }

                // Enqueue this type's supers
                ci.superclasses.forEach { sc ->
                    scan.getClassInfo(sc.name)?.let { queue.add(it) }
                }
                ci.interfaces.forEach { iface ->
                    scan.getClassInfo(iface.name)?.let { queue.add(it) }
                }
            }
            names
        } catch (_: Throwable) {
            emptySet()
        }
        // declared tsFields
        val declaredFieldOriginalNames = mutableSetOf<String>()
        classInfo.fieldInfo.forEach { fi ->
            // Only include fields declared in this class (exclude inherited)
            if (fi.classInfo?.name != classInfo.name) return@forEach
            if ((fi.isStatic) || fi.isSynthetic) return@forEach
            val original = fi.name
            if (original == "INSTANCE" || original.startsWith("$") || original == "Companion") return@forEach
            val fieldAnns: List<AnnotationInfo> = fi.annotationInfo?.toList() ?: emptyList()
            // Resolve name with adapter using field inspection
            val name = jsonAdapter.resolveFieldName(
                classInfo,
                TsFieldInspection.Field(
                    classInfo,
                    fi,
                    ctorParamAnnotations = ctorParamAnnsByName[original] ?: emptyList()
                )
            )
            val effectiveName = if (name == original) {
                // Fallback: if adapter didn't rename via ctor/field/getter, let adapter derive name from ctor param
                jsonAdapter.fallbackNameFromCtorParam(scan, classInfo, original) ?: name
            } else name
            val ts = ensureType(scan, classInfo, fi.typeSignatureOrTypeDescriptor, allTypes)
            val ctorAnns = ctorParamAnnsByName[original] ?: emptyList()
            val allAnns = fieldAnns + ctorAnns
            val isNullable = allAnns.any { it.classInfo.name in config.nullableAnnotations } ||
                    jsonAdapter.isFieldNullable(fieldAnns, emptyList())
            val isOptionalByAnn = allAnns.any { it.classInfo.name in config.optionalAnnotations }
            fields.putIfAbsent(effectiveName, FieldFlags(ts, isNullable, isOptionalByAnn))
            declaredFieldOriginalNames += original
        }
        // Also merge in JavaBean-style getters (including Kotlin property accessors and custom getters)
        classInfo.methodInfo
            .asSequence()
            .filter { mi ->
                // For classes: only include getters declared in this class. For interfaces: allow getters visible here,
                // because Kotlin may compile default implementations into companion DefaultImpls and the declaring info
                // may not strictly match the interface.
                (isInterface || mi.classInfo?.name == classInfo.name) &&
                        !mi.isStatic && !mi.isSynthetic && mi.isPublic && mi.parameterInfo.isEmpty()
            }
            .filter { mi ->
                val n = mi.name
                n != "getClass" && ((n.startsWith("get") && n.length > 3) || (n.startsWith("is") && n.length > 2))
            }
            .forEach { mi ->
                val getterAnns = mi.annotationInfo
                val derivedName = if (mi.name.startsWith("get")) {
                    mi.name.substring(3).replaceFirstChar { it.lowercase() }
                } else {
                    mi.name.substring(2).replaceFirstChar { it.lowercase() }
                }
                // If there's already a declared field backing this property, skip adding the getter-derived field
                if (declaredFieldOriginalNames.contains(derivedName)) return@forEach
                // If this getter property comes from a super type and has no local backing (ctor/field/property), omit it
                if (!isInterface && superGetterPropNames.contains(derivedName)) {
                    val hasLocalBacking = declaredFieldOriginalNames.contains(derivedName) ||
                            ctorParamNames.contains(derivedName) ||
                            kotlinPropertyNames.contains(derivedName)
                    if (!hasLocalBacking) return@forEach
                }
                // For classes: we already restrict to getters declared in this class and avoid duplicates with fields.
                // Allow JavaBean-style getters even if not backed by a Kotlin property (e.g., computed getters).
                // For interfaces: also allowed (handled by the filter above to include visible getters on the interface itself).
                val inspection = TsFieldInspection.Getter(
                    classInfo,
                    mi,
                    ctorParamAnnotations = ctorParamAnnsByName[derivedName] ?: emptyList()
                )
                var prop = jsonAdapter.resolveFieldName(classInfo, inspection)
                if (prop == derivedName) {
                    jsonAdapter.fallbackNameFromCtorParam(scan, classInfo, derivedName)?.let { prop = it }
                }
                val ts = ensureType(
                    scan,
                    classInfo,
                    mi.typeSignatureOrTypeDescriptor?.resultType,
                    allTypes
                )
                val ctorAnns = ctorParamAnnsByName[derivedName] ?: emptyList()
                val allAnns = getterAnns + ctorAnns
                val isNullable = allAnns.any { it.classInfo.name in config.nullableAnnotations } ||
                        jsonAdapter.isFieldNullable(emptyList(), getterAnns)
                val isOptionalByAnn = allAnns.any { it.classInfo.name in config.optionalAnnotations }
                fields.putIfAbsent(prop, FieldFlags(ts, isNullable, isOptionalByAnn))
            }
        return fields.entries.map { it.key to it.value }
    }

    // Adapter now owns ctor-param-based fallback name resolution

    protected fun constructorParamAnnotationsByName(
        scan: ScanResult,
        classInfo: ClassInfo
    ): Map<String, List<AnnotationInfo>> {
        return try {
            // 1) Always let the adapter take precedence if it can pick a specific JSON constructor
            jsonAdapter.chooseJsonConstructor(classInfo, scan)?.let { chosen ->
                val annsByName = LinkedHashMap<String, List<AnnotationInfo>>()
                chosen.parameterInfo.forEach { pi ->
                    val anns = pi.annotationInfo?.toList() ?: emptyList()
                    // Important: map by the JVM/bytecode parameter name so that property lookup by
                    // derived field/getter name can attach these annotations. The adapter will use
                    // ctorParamAnnotations to decide renaming later.
                    val key = pi.name
                    if (!key.isNullOrBlank()) annsByName[key] = anns
                }
                return@let annsByName
            } ?: run {
                // 2) If the adapter didn’t choose, fall back to Kotlin metadata when available
                val km = classInfo.kotlinMetadata()?.kotlinClass()
                if (km != null) {
                    // Choose the constructor with the largest number of value parameters (primary for data classes)
                    val kmCtor = km.constructors.maxByOrNull { it.valueParameters.size } ?: return@run emptyMap()
                    val paramNames = kmCtor.valueParameters.map { it.name }
                    if (paramNames.isEmpty()) return@run emptyMap()

                    // Prefer exact-arity JVM constructor when available; otherwise fall back to the
                    // largest arity (with default mask/marker) that still has at least as many params.
                    val ctors = classInfo.constructorInfo.filter { it.parameterInfo.size >= paramNames.size }
                    val jvmCtor = ctors.firstOrNull { it.parameterInfo.size == paramNames.size }
                        ?: ctors.maxByOrNull { it.parameterInfo.size }
                        ?: return@run emptyMap()

                    val annsByName = LinkedHashMap<String, List<AnnotationInfo>>()
                    val params = jvmCtor.parameterInfo
                    for (i in paramNames.indices) {
                        val pname = paramNames[i]
                        if (i >= params.size) break
                        val pAnns = params[i].annotationInfo?.toList() ?: emptyList()
                        annsByName[pname] = pAnns
                    }
                    annsByName
                } else {
                    // 3) Java fallback with no adapter-selected constructor: nothing to contribute here
                    emptyMap()
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private val building = HashSet<String>()

    protected fun ensureComplexType(scan: ScanResult, ci: ClassInfo, allTypes: MutableMap<String, TsType>) {
        // Pre-register a placeholder to ensure stable naming during recursion
        val aliasName = config.customNaming(ci.name.stripPrefix(ci.packageName + "."))
        if (allTypes[ci.name] == null) {
            ensureAliasUnique(aliasName, ci.name)
            allTypes[ci.name] = TsType(ci.name, aliasName, TsBody.ObjectBody(emptyList()))
        }

        if (building.contains(ci.name)) return
        building.add(ci.name)
        try {
            // Determine generic parameter names for this class/interface
            val genericParams: List<String> = try {
                ci.typeSignature?.typeParameters?.map { it.name } ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            // Create an interface alias (with possible generic args) if needed
            val ifaceAliases: List<String> = run {
                val fromSignatures = try {
                    ci.typeSignature?.superinterfaceSignatures
                } catch (_: Throwable) {
                    null
                }
                val cgRendered: List<String> = if (fromSignatures != null && fromSignatures.isNotEmpty()) {
                    fromSignatures.mapNotNull { si ->
                        val ifaceCi = scan.getClassInfo(si.fullyQualifiedClassName) ?: return@mapNotNull null
                        val baseAlias = interfaceAliasAndRecord(scan, ifaceCi, ci.name, allTypes)
                        val args = si.typeArguments ?: emptyList()
                        if (args.isNotEmpty()) {
                            val rendered = args.map { ta ->
                                val at = toTypeWithBindings(scan, null, ta.typeSignature, allTypes, false)
                                renderTs(at)
                            }
                            baseAlias + "<" + rendered.joinToString(", ") + ">"
                        } else baseAlias
                    }
                } else emptyList()

                // Prefer Kotlin metadata when ClassGraph provided no type args
                val needsKm = cgRendered.isEmpty() || cgRendered.all { !it.contains('<') }
                if (needsKm) {
                    val km = try {
                        ci.kotlinMetadata()?.kotlinClass()
                    } catch (_: Throwable) {
                        null
                    }
                    val fromKm: List<String> = km?.supertypes?.mapNotNull { kmType ->
                        val cls = kmType.classifier
                        if (cls is KmClassifier.Class) {
                            val fqn = cls.name.replace('/', '.')
                            val ifaceCi = scan.getClassInfo(fqn)
                            if (ifaceCi != null) {
                                val baseAlias = interfaceAliasAndRecord(scan, ifaceCi, ci.name, allTypes)
                                val args = kmType.arguments
                                if (args.isNotEmpty()) {
                                    val rendered = args.mapNotNull { a -> a.type?.let { mapKmTypeToTsName(it) } }
                                    if (rendered.isNotEmpty()) baseAlias + "<" + rendered.joinToString(", ") + ">" else baseAlias
                                } else baseAlias
                            } else null
                        } else null
                    } ?: emptyList()
                    fromKm.ifEmpty {
                        cgRendered.ifEmpty {
                            // Fallback: no signature info; use raw interfaces
                            ci.interfaces.mapNotNull { ifaceRef ->
                                val ifaceCi = scan.getClassInfo(ifaceRef.name)
                                ifaceCi?.let { interfaceAliasAndRecord(scan, it, ci.name, allTypes) }
                            }
                        }
                    }
                } else cgRendered
            }
            val fields = extractFields(scan, ci, allTypes)

            // If intersected interfaces are generic, but ClassGraph/KM didn't provide type args,
            // attempt to infer them from this class's fields by matching names.
            fun baseAliasName(n: String): String = n.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
            val ifaceAliasesResolved: List<String> = run {
                if (fields.isNotEmpty() && ifaceAliases.any { !it.contains('<') }) {
                    val byNameRendered = fields.associate { (n, fl) -> n to renderTs(fl.ts) }
                    val adjusted = mutableListOf<String>()
                    ifaceAliases.forEach { inter ->
                        val base = baseAliasName(inter)
                        val ifaceType = allTypes.values.firstOrNull { it.typeScriptTypeName == base }
                        val gp = ifaceType?.genericParameters ?: emptyList()
                        adjusted += if (gp.isNotEmpty()) {
                            // Order args by interface field order
                            val ifaceFields = ((ifaceType?.body) as? TsBody.ObjectBody)?.tsFields ?: emptyList()
                            val args = ifaceFields.mapNotNull { f -> byNameRendered[f.name] }
                            if (args.size == ifaceFields.size && args.isNotEmpty()) {
                                base + "<" + args.joinToString(", ") + ">"
                            } else inter
                        } else inter
                    }
                    adjusted.ifEmpty { ifaceAliases }
                } else ifaceAliases
            }
            val optionalParams = kotlinOptionalConstructorParams(ci)
            val hasKm = try {
                ci.kotlinMetadata()?.kotlinClass() != null
            } catch (_: Throwable) {
                false
            }
            val tsFieldTypes = fields.map { (name, flags) ->
                var tsType = if (flags.nullable) union(flags.ts, primitive("null")) else flags.ts
                // Heuristic: if this class/interface is generic and a field resolved to 'any',
                // try to map it to a generic parameter by name or default to the single type param.
                if (genericParams.isNotEmpty()) {
                    val isAny = (tsType.body as? TsBody.PrimitiveBody)?.tsName == "any"
                    if (isAny) {
                        val idx = genericParams.indexOfFirst { gp -> gp.equals(name, ignoreCase = true) }
                        val gpName = when {
                            idx >= 0 -> genericParams[idx]
                            genericParams.size == 1 -> genericParams[0]
                            else -> null
                        }
                        if (gpName != null) tsType = primitive(gpName)
                    }
                }
                TsField(
                    name,
                    tsType,
                    // For Kotlin classes, preserve legacy behavior (constructor/defaults only).
                    // For non-Kotlin classes (e.g., Java POJOs), also respect optional-by-annotation.
                    optional = (name in optionalParams) || (!hasKm && flags.optionalByAnnotation),
                    nullable = flags.nullable
                )
            }

            val existing = allTypes[ci.name]
            if (existing == null) {
                // Should not happen due to pre-registration, but keep a safe path
                allTypes[ci.name] =
                    TsType(
                        ci.name,
                        aliasName,
                        TsBody.ObjectBody(tsFieldTypes),
                        intersects = ifaceAliasesResolved,
                        genericParameters = genericParams
                    )
            } else {
                // Merge with existing tsFields to avoid downgrading optional flags
                val prevFields = (existing.body as? TsBody.ObjectBody)?.tsFields?.associateBy { it.name } ?: emptyMap()
                val mergedFields = tsFieldTypes.map { nf ->
                    val prev = prevFields[nf.name]
                    if (prev != null) nf.copy(optional = prev.optional || nf.optional) else nf
                }
                allTypes[ci.name] = existing.copy(
                    body = TsBody.ObjectBody(mergedFields),
                    intersects = ifaceAliasesResolved,
                    genericParameters = genericParams
                )
            }
        } finally {
            building.remove(ci.name)
        }
    }

    protected fun mapKmTypeToTsName(t: KmType): String? {
        return when (val cls = t.classifier) {
            is KmClassifier.Class -> {
                when (val fq = cls.name.replace('/', '.')) {
                    "kotlin.String" -> "string"
                    "kotlin.Boolean" -> "boolean"
                    "kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float" -> "number"
                    else -> fq.substringAfterLast('.')
                }
            }

            is KmClassifier.TypeAlias -> cls.name.substringAfterLast('.')
            is KmClassifier.TypeParameter -> cls.id.toString()
        }
    }

    // Helper to ensure an interface type exists, record referenced-by relation, and return its alias
    protected fun interfaceAliasAndRecord(
        scan: ScanResult,
        ifaceCi: ClassInfo,
        ownerFqn: String,
        allTypes: MutableMap<String, TsType>
    ): String {
        ensureComplexType(scan, ifaceCi, allTypes)
        allTypes[ifaceCi.name]?.let { ifaceType ->
            val existing = ifaceType.references.toMutableList()
            if (existing.filterIsInstance<ByType>().none { r -> r.jvmQualifiedClassName == ownerFqn }) {
                existing += ByType(ownerFqn)
                allTypes[ifaceCi.name] = ifaceType.copy(references = existing)
            }
        }
        return config.customNaming(
            ifaceCi.name.stripPrefix(ifaceCi.packageName + ".")
        )
    }

    protected fun kotlinOptionalConstructorParams(classInfo: ClassInfo): Set<String> {
        // Quick checks
        if (classInfo.isInterface) return emptySet()
        if (classInfo.constructorInfo.filter { it.parameterInfo.isNotEmpty() }.isEmpty())
            return emptySet()

        // Load kotlin class
        val kmClass = classInfo.kotlinMetadata()?.kotlinClass()
        if (kmClass != null) {
            // Collect defaults from any constructor metadata to be resilient to edge cases
            return kmClass.constructors.flatMap { ctor ->
                ctor.valueParameters.filter { it.declaresDefaultValue }.map { it.name }
            }.toSet()

        }
        return emptySet()
    }

    // New: Build extraction result from a framework-agnostic ApiRegistry
    fun buildFromRegistry(
        scan: ScanResult,
        registry: ApiRegistry
    ): ExtractionResult {
        // Reset alias collision tracking for this run
        aliasToJvm = LinkedHashMap()
        val allTypes = LinkedHashMap<String, TsType>() // key: JVM FQN

        val apis = registry.apis.mapNotNull { apiDesc ->
            val controllerInfo = scan.getClassInfo(apiDesc.controllerFqn) ?: return@mapNotNull null
            val apiMethods = apiDesc.methods.mapNotNull { mdesc ->
                val mi = controllerInfo.methodInfo.firstOrNull { it.name == mdesc.name } ?: return@mapNotNull null
                val http = try {
                    com.iodesystems.ts.model.TsHttpMethod.valueOf(mdesc.http.name)
                } catch (_: Throwable) {
                    com.iodesystems.ts.model.TsHttpMethod.GET
                }
                val base = (apiDesc.basePath ?: "").ifBlank { "" }
                val methodPath = mdesc.path
                val fullPath = when {
                    methodPath.isBlank() && base.isBlank() -> "/"
                    methodPath.isBlank() -> base
                    base.isBlank() -> if (methodPath.startsWith("/")) methodPath else "/$methodPath"
                    else -> {
                        val sep = if (base.endsWith("/") || methodPath.startsWith("/")) "" else "/"
                        (base + sep + methodPath).replace(Regex("/+"), "/")
                    }
                }

                // Request body type by index if supplied
                var reqType = mdesc.bodyIndex?.let { idx ->
                    val pi = mi.parameterInfo.getOrNull(idx)
                    val sig = pi?.typeSignature ?: pi?.typeSignatureOrTypeDescriptor
                    sig?.let { toType(scan, controllerInfo, it, allTypes) }
                }

                // Best-effort Kotlin metadata enhancement to preserve generic args for request type
                if (reqType != null && mdesc.bodyIndex!! >= 0) {
                    try {
                        val kmClass = controllerInfo.kotlinMetadata()?.kotlinClass()
                        val kmFun = kmClass?.functions?.firstOrNull { it.name == mi.name }
                        if (kmFun != null && kmFun.valueParameters.size > mdesc.bodyIndex) {
                            val paramKmType = kmFun.valueParameters[mdesc.bodyIndex].type
                            val args = paramKmType.arguments
                            if (args.isNotEmpty()) {
                                val renderedArgs = args.mapNotNull { arg ->
                                    arg.type?.let { mapKmTypeToTsName(it) }
                                }
                                if (renderedArgs.isNotEmpty()) {
                                    val alias = reqType.typeScriptTypeName.substringBefore("<")
                                    val tsName = alias + "<" + renderedArgs.joinToString(", ") + ">"
                                    reqType =
                                        TsType(reqType.jvmQualifiedClassName, tsName, TsBody.PrimitiveBody(tsName))
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // ignore metadata issues
                    }
                }

                // Response type from method signature
                val returnSig = mi.typeSignature?.resultType ?: mi.typeSignatureOrTypeDescriptor?.resultType
                val resType = toType(scan, controllerInfo, returnSig, allTypes, isReturn = true)

                // Build query params bag from provided params marked as Query
                val queryFields = mdesc.params.filter { it.kind.name == "QUERY" }.mapNotNull { pd ->
                    val pi = mi.parameterInfo.getOrNull(pd.index) ?: return@mapNotNull null
                    val psig = pi.typeSignature ?: pi.typeSignatureOrTypeDescriptor
                    val t = toType(scan, controllerInfo, psig, allTypes)
                    TsField(pd.name, t, optional = pd.optional, nullable = false)
                }

                // Build path params map: placeholder -> TsField
                val pathFields: Map<String, TsField> = mdesc.params.filter { it.kind.name == "PATH" }.mapNotNull { pd ->
                    val pi = mi.parameterInfo.getOrNull(pd.index) ?: return@mapNotNull null
                    val psig = pi.typeSignature ?: pi.typeSignatureOrTypeDescriptor
                    val t = toType(scan, controllerInfo, psig, allTypes)
                    val placeholder = pd.placeholder ?: pd.name
                    placeholder to TsField(pd.name, t, optional = pd.optional, nullable = false)
                }.toMap()

                fun inlineTs(t: TsType): String = when (val b = t.body) {
                    is TsBody.PrimitiveBody -> t.typeScriptTypeName
                    is TsBody.UnionBody -> b.options.joinToString(" | ") { inlineTs(it) }
                    is TsBody.ArrayBody -> inlineTs(b.element) + "[]"
                    is TsBody.ObjectBody -> {
                        val inner = b.tsFields.joinToString(", ") { f ->
                            val q = if (f.optional) "?" else ""
                            "${f.name}$q: ${inlineTs(f.type)}"
                        }
                        "{ $inner }"
                    }
                }

                val queryType = if (queryFields.isNotEmpty()) TsType(
                    jvmQualifiedClassName = "",
                    typeScriptTypeName = "{ " + queryFields.joinToString(", ") {
                        val o = if (it.optional) "?" else ""
                        "${it.name}$o: ${inlineTs(it.type)}"
                    } + " }",
                    body = TsBody.ObjectBody(queryFields)
                ) else null

                com.iodesystems.ts.model.ApiMethod(
                    name = mi.name,
                    httpMethod = http,
                    path = fullPath,
                    requestBodyType = reqType,
                    queryParamsType = queryType,
                    responseBodyType = resType,
                    pathTsFields = pathFields
                )
            }
            com.iodesystems.ts.model.ApiModel(
                cls = controllerInfo.simpleName,
                jvmQualifiedClassName = controllerInfo.name,
                basePath = apiDesc.basePath ?: "",
                apiMethods = apiMethods
            )
        }

        // Traverse to collect references and ensure types (method + dependent types)
        if (apis.isNotEmpty()) {
            val typesByAlias: Map<String, TsType> = allTypes.values.associateBy { it.typeScriptTypeName }

            fun addMethodRef(methodFqn: String, target: TsType) {
                val jvm = target.jvmQualifiedClassName
                var existing = allTypes[jvm]
                if (existing == null) {
                    val ci = scan.getClassInfo(jvm)
                    if (ci != null) {
                        ensureComplexType(scan, ci, allTypes)
                        existing = allTypes[jvm]
                    }
                    if (existing == null) {
                        if (target.body is TsBody.ObjectBody || target.body is TsBody.UnionBody) {
                            allTypes[jvm] = target
                            existing = target
                        } else {
                            return
                        }
                    }
                }
                val merged = existing.references.toMutableList()
                if (merged.filterIsInstance<com.iodesystems.ts.model.TsRef.ByMethod>()
                        .none { it.controllerJvmQualifiedMethodName == methodFqn }
                ) {
                    merged += com.iodesystems.ts.model.TsRef.ByMethod(methodFqn)
                    allTypes[jvm] = existing.copy(references = merged)
                }
            }

            fun baseAliasName(name: String): String =
                name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")

            fun visit(methodFqn: String, t: TsType, seen: MutableSet<String>) {
                val alias = t.typeScriptTypeName
                if (!seen.add(alias)) return
                when (val body = t.body) {
                    is TsBody.ObjectBody -> {
                        val registered = typesByAlias[alias] ?: t
                        addMethodRef(methodFqn, registered)
                        body.tsFields.forEach { f ->
                            fun visitFieldType(ft: TsType) {
                                when (val fb = ft.body) {
                                    is TsBody.PrimitiveBody -> {
                                        typesByAlias[fb.tsName]?.let { resolved -> visit(methodFqn, resolved, seen) }
                                    }

                                    is TsBody.UnionBody -> fb.options.forEach { opt -> visitFieldType(opt) }
                                    is TsBody.ArrayBody -> visitFieldType(fb.element)
                                    is TsBody.ObjectBody -> visit(methodFqn, ft, seen)
                                }
                            }
                            visitFieldType(f.type)
                        }
                    }

                    is TsBody.UnionBody -> body.options.forEach { opt -> visit(methodFqn, opt, seen) }
                    is TsBody.PrimitiveBody -> {
                        val base = baseAliasName(body.tsName)
                        typesByAlias[base]?.let { resolved -> visit(methodFqn, resolved, seen) }
                    }

                    is TsBody.ArrayBody -> visit(methodFqn, body.element, seen)
                }
            }

            apis.forEach { api ->
                api.apiMethods.forEach { m ->
                    val methodFqn = api.jvmQualifiedClassName + "." + m.name
                    m.requestBodyType?.let { visit(methodFqn, it, mutableSetOf()) }
                    m.responseBodyType?.let { visit(methodFqn, it, mutableSetOf()) }
                }
            }
        }

        val extraction = ExtractionResult(apis, allTypes.values.toList())
        return extraction
    }
}