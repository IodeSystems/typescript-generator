package com.iodesystems.ts.extractor.extractors

import com.iodesystems.ts.Config
import com.iodesystems.ts.adapter.JsonAdapter
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsType
import io.github.classgraph.ClassInfo
import io.github.classgraph.ScanResult
import kotlinx.metadata.*
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * Converts Java reflection types to TsType representations.
 * Uses ClassGraph's loadClass() for full reflection + Kotlin metadata for nullability.
 */
class ClassReference(
    private val config: Config,
    private val scan: ScanResult,
    private val typeCache: MutableMap<String, TsType> = mutableMapOf(),
    private val jsonAdapter: JsonAdapter? = null
) {
    companion object {
        private val PRIMITIVE_MAPPINGS = mapOf(
            "boolean" to "boolean",
            "java.lang.Boolean" to "boolean",
            "kotlin.Boolean" to "boolean",
            "byte" to "number",
            "java.lang.Byte" to "number",
            "kotlin.Byte" to "number",
            "short" to "number",
            "java.lang.Short" to "number",
            "kotlin.Short" to "number",
            "int" to "number",
            "java.lang.Integer" to "number",
            "kotlin.Int" to "number",
            "long" to "number",
            "java.lang.Long" to "number",
            "kotlin.Long" to "number",
            "float" to "number",
            "java.lang.Float" to "number",
            "kotlin.Float" to "number",
            "double" to "number",
            "java.lang.Double" to "number",
            "kotlin.Double" to "number",
            "java.lang.Number" to "number",
            "kotlin.Number" to "number",
            "char" to "string",
            "java.lang.Character" to "string",
            "kotlin.Char" to "string",
            "java.lang.String" to "string",
            "kotlin.String" to "string",
        )

        private val COLLECTION_TYPES = setOf(
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "kotlin.collections.List",
            "kotlin.collections.Collection",
            "kotlin.collections.Set",
            "kotlin.collections.MutableList",
            "kotlin.collections.MutableCollection",
            "kotlin.collections.MutableSet",
        )

        private val MAP_TYPES = setOf(
            "java.util.Map",
            "kotlin.collections.Map",
            "kotlin.collections.MutableMap",
        )

        private val BOOLEAN_TYPES = setOf(
            "boolean",
            "java.lang.Boolean",
            "kotlin.Boolean",
        )
    }

    /**
     * Checks if a parameterized type contains self-references in its type arguments.
     * TypeScript cannot handle certain self-referential types (e.g., class K : Iterable<K>).
     *
     * @param clazz The class being processed
     * @param parameterizedType The generic superclass or interface to check
     * @param description A description for the error message (e.g., "interface Iterable")
     * @throws IllegalStateException if a self-referential type is detected
     */
    private fun checkSelfReferentialType(
        clazz: Class<*>,
        parameterizedType: ParameterizedType,
        description: String
    ) {
        val actualTypeArgs = parameterizedType.actualTypeArguments
        actualTypeArgs.forEach { typeArg ->
            when (typeArg) {
                // Direct self-reference: class K : Iterable<K>
                is Class<*> -> {
                    if (typeArg == clazz) {
                        throw IllegalStateException(
                            "Self-referential type detected: ${clazz.name} references itself as a type argument " +
                            "in $description. TypeScript cannot properly represent this pattern. " +
                            "Consider using config.exclude(\"${clazz.name}\") " +
                            "to exclude this type from generation."
                        )
                    }
                }
                // Parameterized self-reference: class K<T> : Iterable<K<T>>
                is ParameterizedType -> {
                    val rawType = typeArg.rawType
                    if (rawType is Class<*> && rawType == clazz) {
                        throw IllegalStateException(
                            "Self-referential type detected: ${clazz.name} references itself as a type argument " +
                            "in $description. TypeScript cannot properly represent this pattern. " +
                            "Consider using config.exclude(\"${clazz.name}\") " +
                            "to exclude this type from generation."
                        )
                    }
                }
            }
        }
    }

    /**
     * Derives the JSON property name from a getter method using Jackson naming conventions.
     * Applies the configured settings for is-getter detection and bean naming.
     *
     * @param methodName The getter method name (e.g., "getName", "isActive", "isOptional")
     * @param returnTypeName The fully qualified return type name
     * @return The derived property name (e.g., "name", "active", "optional")
     */
    private fun derivePropertyNameFromGetter(methodName: String, returnTypeName: String): String {
        val isBoolean = BOOLEAN_TYPES.contains(returnTypeName)

        return when {
            // Handle is* getters
            methodName.startsWith("is") && methodName.length > 2 -> {
                val shouldStripIs = when {
                    isBoolean -> config.autoDetectIsGetters
                    else -> config.allowIsGettersForNonBoolean
                }
                if (shouldStripIs) {
                    applyBeanNamingConvention(methodName.substring(2))
                } else {
                    // Keep as-is (the property name is the method name)
                    methodName
                }
            }
            // Handle get* getters
            methodName.startsWith("get") && methodName.length > 3 -> {
                applyBeanNamingConvention(methodName.substring(3))
            }
            // Fallback - use method name as-is
            else -> methodName
        }
    }

    /**
     * Applies JavaBeans naming convention to a property name derived from a getter.
     * With useStdBeanNaming=false (Jackson default): lowercase leading uppercase chars -> "URL" becomes "url"
     * With useStdBeanNaming=true (standard beans): only lowercase if followed by lowercase -> "URL" stays "URL"
     *
     * Jackson's default behavior (BeanUtil.legacyManglePropertyName) lowercases all leading uppercase
     * characters that are followed by another uppercase character, then lowercases the final one if
     * followed by lowercase or end of string.
     */
    private fun applyBeanNamingConvention(name: String): String {
        if (name.isEmpty()) return name

        return if (config.useStdBeanNaming) {
            // Standard JavaBeans: only lowercase first char if second char is lowercase or doesn't exist
            if (name.length == 1 || name[1].isLowerCase()) {
                name.replaceFirstChar { it.lowercase() }
            } else {
                name // Keep as-is (e.g., "URL" stays "URL")
            }
        } else {
            // Jackson default: lowercase initial sequence of uppercase characters
            // "URL" -> "url", "URLConnection" -> "urlConnection", "FOptional" -> "fOptional", "Active" -> "active"
            if (name.isEmpty()) return name

            val sb = StringBuilder()
            var i = 0
            while (i < name.length) {
                val c = name[i]
                if (c.isUpperCase()) {
                    val nextIdx = i + 1
                    val nextIsLower = nextIdx < name.length && name[nextIdx].isLowerCase()
                    val isLast = nextIdx >= name.length

                    if (nextIsLower && i > 0) {
                        // This uppercase is followed by lowercase and we've already processed some chars
                        // Keep this uppercase and append the rest
                        sb.append(name.substring(i))
                        break
                    } else {
                        // Lowercase this char and continue
                        sb.append(c.lowercaseChar())
                        if (isLast || nextIsLower) {
                            // Last char or next is lowercase - append the rest
                            if (!isLast) sb.append(name.substring(nextIdx))
                            break
                        }
                        i++
                    }
                } else {
                    // Hit a lowercase char - append the rest
                    sb.append(name.substring(i))
                    break
                }
            }
            sb.toString()
        }
    }

    /**
     * Convert a Java reflection Type to a TsType.
     * @param type The Java reflection type
     * @param nullable Whether this type is nullable (from Kotlin metadata or annotations)
     * @param optional Whether this type is optional (has a default value)
     * @param typeParams Type parameters inherited from outer context (for generic resolution)
     * @param kmType Optional Kotlin type metadata for extracting nullability of type arguments
     */
    fun toTsType(
        type: Type,
        nullable: Boolean = false,
        optional: Boolean = false,
        typeParams: Map<String, TsType.Inline> = emptyMap(),
        kmType: KmType? = null
    ): TsType {
        // Use KmType nullability if available, otherwise use provided nullable parameter
        val isNullable = kmType?.isNullable ?: nullable
        return when (type) {
            is Class<*> -> classToTsType(type, isNullable, optional, typeParams)
            is ParameterizedType -> parameterizedToTsType(type, isNullable, optional, typeParams, kmType)
            is TypeVariable<*> -> typeVariableToTsType(type, isNullable, optional, typeParams)
            is WildcardType -> wildcardToTsType(type, isNullable, optional, typeParams)
            else -> TsType.Inline(
                fqcn = type.typeName,
                name = "any",
                nullable = isNullable,
                optional = optional
            )
        }
    }

    private fun classToTsType(
        clazz: Class<*>,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>
    ): TsType {
        val fqcn = clazz.name

        // Check for user-configured mapped types
        config.mapType[fqcn]?.let { mappedValue ->
            // Create an alias type and register it if not already cached
            val simpleName = fqcn.removePrefix(clazz.packageName.plus("."))
            val tsName = config.customNaming(fqcn, simpleName)
            if (!typeCache.containsKey(fqcn)) {
                typeCache[fqcn] = TsType.Alias(
                    fqcn = fqcn,
                    name = tsName,
                    aliasTo = mappedValue
                )
            }
            // Return an inline reference to the alias
            return TsType.Inline(
                fqcn = fqcn,
                name = tsName,
                nullable = nullable,
                optional = optional
            )
        }

        // Check for primitives
        PRIMITIVE_MAPPINGS[fqcn]?.let { primitiveName ->
            return TsType.Inline(
                fqcn = fqcn,
                name = primitiveName,
                nullable = nullable,
                optional = optional
            )
        }

        // Check if it should be omitted
        if (!config.includeType(fqcn)) {
            return TsType.Inline(
                fqcn = fqcn,
                name = "any",
                nullable = nullable,
                optional = optional
            )
        }

        // Handle void/Unit
        if (clazz == Void.TYPE || clazz == Void::class.java || fqcn == "kotlin.Unit") {
            return TsType.Inline(
                fqcn = fqcn,
                name = "void",
                nullable = false,
                optional = false
            )
        }

        // Handle arrays
        if (clazz.isArray) {
            val componentType = clazz.componentType
            val elementType = toTsType(componentType, false, false, typeParams)
            return TsType.Inline(
                fqcn = fqcn,
                name = "Array<T>",
                nullable = nullable,
                optional = optional,
                generics = mapOf("T" to elementType.inlineReference())
            )
        }

        // Handle enums
        if (clazz.isEnum) {
            return extractEnum(clazz, nullable, optional)
        }

        // Handle collections without type parameters (raw types)
        if (COLLECTION_TYPES.any { clazz.name == it || clazz.interfaces.any { i -> i.name == it } }) {
            return TsType.Inline(
                fqcn = fqcn,
                name = "Array<T>",
                nullable = nullable,
                optional = optional,
                generics = mapOf("T" to TsType.Inline(fqcn = "any", name = "any"))
            )
        }

        // Handle maps without type parameters (raw types)
        if (MAP_TYPES.any { clazz.name == it || clazz.interfaces.any { i -> i.name == it } }) {
            return TsType.Inline(
                fqcn = fqcn,
                name = "Record<K,V>",
                nullable = nullable,
                optional = optional,
                generics = mapOf(
                    "K" to TsType.Inline(fqcn = "any", name = "string"),
                    "V" to TsType.Inline(fqcn = "any", name = "any")
                )
            )
        }

        // Check for discriminated union types (@JsonTypeInfo)
        val discriminatedSubTypes = jsonAdapter?.resolveDiscriminatedSubTypes(scan, clazz)
        if (discriminatedSubTypes != null) {
            return extractUnionType(clazz, discriminatedSubTypes, nullable, optional, typeParams)
        }

        // Complex object type - extract fields
        return extractObjectType(clazz, nullable, optional, typeParams)
    }

    private fun parameterizedToTsType(
        type: ParameterizedType,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>,
        kmType: KmType? = null
    ): TsType {
        val rawType = type.rawType as Class<*>
        val fqcn = rawType.name
        val actualTypeArgs = type.actualTypeArguments
        val kmArgs = kmType?.arguments ?: emptyList()

        // Check for user-configured mapped types
        config.mapType[fqcn]?.let { mappedName ->
            // Handle generic mapped types like Optional<T> -> "T | null"
            val generics = if (actualTypeArgs.isNotEmpty()) {
                val nestedKmType = kmArgs.getOrNull(0)?.type
                mapOf("T" to toTsType(actualTypeArgs[0], false, false, typeParams, nestedKmType).inlineReference())
            } else emptyMap()
            return TsType.Inline(
                fqcn = fqcn,
                name = mappedName,
                nullable = nullable,
                optional = optional,
                generics = generics
            )
        }

        // Handle collections
        if (COLLECTION_TYPES.any { rawType.name == it }) {
            val nestedKmType = kmArgs.getOrNull(0)?.type
            val elementType = if (actualTypeArgs.isNotEmpty()) {
                toTsType(actualTypeArgs[0], false, false, typeParams, nestedKmType)
            } else {
                TsType.Inline(fqcn = "any", name = "any")
            }
            return TsType.Inline(
                fqcn = fqcn,
                name = "Array<T>",
                nullable = nullable,
                optional = optional,
                generics = mapOf("T" to elementType.inlineReference())
            )
        }

        // Handle maps
        if (MAP_TYPES.any { rawType.name == it }) {
            val keyKmType = kmArgs.getOrNull(0)?.type
            val valueKmType = kmArgs.getOrNull(1)?.type
            val keyType = if (actualTypeArgs.isNotEmpty()) {
                toTsType(actualTypeArgs[0], false, false, typeParams, keyKmType)
            } else {
                TsType.Inline(fqcn = "any", name = "string")
            }
            val valueType = if (actualTypeArgs.size > 1) {
                toTsType(actualTypeArgs[1], false, false, typeParams, valueKmType)
            } else {
                TsType.Inline(fqcn = "any", name = "any")
            }
            return TsType.Inline(
                fqcn = fqcn,
                name = "Record<K,V>",
                nullable = nullable,
                optional = optional,
                generics = mapOf(
                    "K" to keyType.inlineReference(),
                    "V" to valueType.inlineReference()
                )
            )
        }

        // Complex generic type - build reference with type parameters
        // First, ensure the raw type is extracted and cached (goes through classToTsType for union detection)
        val extractedRawType = classToTsType(rawType, false, false, typeParams)

        // Get the base name - use the extracted type's name if it differs (e.g., Union types have "Union" suffix)
        val typeParamNames = rawType.typeParameters.map { it.name }
        val resolvedGenerics = typeParamNames.zip(actualTypeArgs).mapIndexed { idx, (paramName, argType) ->
            val nestedKmType = kmArgs.getOrNull(idx)?.type
            paramName to toTsType(argType, false, false, typeParams, nestedKmType).inlineReference()
        }.toMap()

        // Use the extracted type's base name (handles Union suffix properly)
        val baseName = extractedRawType.name.let { name ->
            // Remove any existing generic params from the name to get base
            if (name.contains("<")) name.substringBefore("<") else name
        }

        return TsType.Inline(
            fqcn = fqcn,
            name = if (resolvedGenerics.isNotEmpty()) "$baseName<${resolvedGenerics.keys.joinToString(",")}>" else baseName,
            nullable = nullable,
            optional = optional,
            generics = resolvedGenerics
        )
    }

    private fun typeVariableToTsType(
        typeVar: TypeVariable<*>,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>
    ): TsType {
        // If we have a resolved type parameter, use it
        typeParams[typeVar.name]?.let { resolved ->
            return resolved.copy(nullable = nullable || resolved.nullable, optional = optional || resolved.optional)
        }

        // Otherwise return the type variable name as-is
        return TsType.Inline(
            fqcn = typeVar.name,
            name = typeVar.name,
            nullable = nullable,
            optional = optional
        )
    }

    private fun wildcardToTsType(
        wildcard: WildcardType,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>
    ): TsType {
        // For wildcards, use the upper bound if available
        val upperBounds = wildcard.upperBounds
        return if (upperBounds.isNotEmpty() && upperBounds[0] != Any::class.java) {
            toTsType(upperBounds[0], nullable, optional, typeParams)
        } else {
            TsType.Inline(
                fqcn = "any",
                name = "any",
                nullable = nullable,
                optional = optional
            )
        }
    }

    private fun extractEnum(
        clazz: Class<*>,
        nullable: Boolean,
        optional: Boolean
    ): TsType {
        val fqcn = clazz.name

        // Check cache first
        typeCache[fqcn]?.let {
            return it.inlineReference(nullable = nullable, optional = optional)
        }

        // Use the name after the package (includes outer class names)
        val simpleName = fqcn.removePrefix(clazz.packageName.plus("."))
        val tsName = config.customNaming(fqcn, simpleName)

        // Use jsonAdapter to get serialized values (e.g., @JsonValue support)
        val enumNames = clazz.enumConstants.map { (it as Enum<*>).name }
        val unionLiteral = jsonAdapter?.enumSerializedTypeOrNull(scan, fqcn, enumNames)
            ?: enumNames.joinToString(" | ") { "'$it'" }

        val result = TsType.Enum(
            fqcn = fqcn,
            name = tsName,
            nullable = nullable,
            optional = optional,
            unionLiteral = unionLiteral
        )

        // Cache the enum type
        typeCache[fqcn] = result
        return result
    }

    private fun extractUnionType(
        clazz: Class<*>,
        discriminatedSubTypes: JsonAdapter.ResolvedDiscriminatedSubTypes,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>
    ): TsType {
        val fqcn = clazz.name
        // Use the name after the package (includes outer class names) + "Union" suffix
        val simpleName = fqcn.removePrefix(clazz.packageName.plus("."))
        val baseTsName = config.customNaming(fqcn, simpleName)
        val interfaceTsName = if (clazz.typeParameters.isNotEmpty()) {
            "$baseTsName<${clazz.typeParameters.joinToString(",") { it.name }}>"
        } else {
            baseTsName
        }
        val unionTsName = if (clazz.typeParameters.isNotEmpty()) {
            "${baseTsName}Union<${clazz.typeParameters.joinToString(",") { it.name }}>"
        } else {
            "${baseTsName}Union"
        }

        // Check cache
        val cacheKey = "$fqcn:Union"
        typeCache[cacheKey]?.let {
            return it.inlineReference(nullable = nullable, optional = optional)
        }

        // Extract interfaces that this union interface extends (for union supertypes and interface intersections)
        val interfaceIntersections = mutableListOf<TsType>()
        clazz.interfaces.forEach { iface ->
            if (config.includeType(iface.name)) {
                val ifaceType = toTsType(iface, false, false, typeParams)
                interfaceIntersections.add(ifaceType)
            }
        }

        // Collect inherited field names from the union interface hierarchy to exclude from children
        val inheritedFromInterfaces = collectInheritedFieldNames(clazz)

        // Also create an Object type for the interface itself
        val classInfo = scan.getClassInfo(fqcn)
        val kmClass: KmClass? = classInfo?.kotlinClass()
        val interfaceFields = extractFields(clazz, kmClass, typeParams, inheritedFromInterfaces)
        val interfaceType = TsType.Object(
            fqcn = fqcn,
            name = interfaceTsName,
            nullable = false,
            optional = false,
            fields = interfaceFields,
            generics = if (clazz.typeParameters.isNotEmpty()) {
                clazz.typeParameters.associate { tp ->
                    tp.name to TsType.Inline(fqcn = tp.name, name = tp.name)
                }
            } else emptyMap(),
            intersections = interfaceIntersections
        )
        typeCache[fqcn] = interfaceType

        // Pre-register to handle recursive types
        val placeholder = TsType.Union(
            fqcn = fqcn,
            name = unionTsName,
            nullable = nullable,
            optional = optional,
            discriminatorField = discriminatedSubTypes.discriminatorProperty,
            children = emptyList(),
            supertypes = listOf(interfaceType.inlineReference())
        )
        typeCache[cacheKey] = placeholder

        // Collect field names from the union interface (to exclude from children)
        val unionInterfaceFields = getPropertyNames(clazz)
        val childExcludeFields = unionInterfaceFields + inheritedFromInterfaces

        // Extract child types
        val children = discriminatedSubTypes.options.map { option ->
            val childClass = option.shim
            val childFqcn = childClass.name
            val childSimpleName = childFqcn.removePrefix(childClass.packageName.plus("."))
            val childBaseName = config.customNaming(childFqcn, childSimpleName)

            // Extract the child type as an Object with discriminator field
            val childFields = mutableMapOf<String, TsField>()

            // Add discriminator field with literal type
            childFields[discriminatedSubTypes.discriminatorProperty] = TsField(
                type = TsType.Inline(
                    fqcn = "literal",
                    name = "\"${option.discriminatorValue}\"",
                    nullable = false,
                    optional = false
                ),
                optional = false,
                nullable = false
            )

            // Extract other fields from the child class, excluding inherited ones
            val childClassInfo = scan.getClassInfo(childFqcn)
            val childKmClass: KmClass? = childClassInfo?.kotlinClass()
            val otherFields = extractFields(childClass, childKmClass, typeParams, childExcludeFields)
            childFields.putAll(otherFields)

            val childType = TsType.Object(
                fqcn = childFqcn,
                name = childBaseName,
                nullable = false,
                optional = false,
                fields = childFields,
                discriminator = discriminatedSubTypes.discriminatorProperty to option.discriminatorValue,
                intersections = listOf(interfaceType.inlineReference()) // Child extends parent interface
            )
            typeCache[childFqcn] = childType
            childType
        }

        val result = TsType.Union(
            fqcn = fqcn,
            name = unionTsName,
            nullable = nullable,
            optional = optional,
            discriminatorField = discriminatedSubTypes.discriminatorProperty,
            children = children,
            supertypes = listOf(interfaceType.inlineReference()), // Union intersects with parent interface
            generics = if (clazz.typeParameters.isNotEmpty()) {
                clazz.typeParameters.associate { tp ->
                    tp.name to TsType.Inline(fqcn = tp.name, name = tp.name)
                }
            } else emptyMap()
        )

        typeCache[cacheKey] = result
        return result
    }

    /**
     * Collects all field/property names from a class's parent hierarchy (superclass + interfaces).
     * Used to exclude inherited fields from child type definitions.
     */
    private fun collectInheritedFieldNames(clazz: Class<*>): Set<String> {
        val inheritedFields = mutableSetOf<String>()

        // Collect from superclass
        val superClass = clazz.superclass
        if (superClass != null &&
            superClass != Any::class.java &&
            superClass != Object::class.java &&
            config.includeType(superClass.name)) {
            inheritedFields.addAll(getPropertyNames(superClass))
        }

        // Collect from interfaces
        clazz.interfaces.forEach { iface ->
            if (config.includeType(iface.name)) {
                inheritedFields.addAll(getPropertyNames(iface))
            }
        }

        return inheritedFields
    }

    /**
     * Gets all property names from a class (from Kotlin metadata or Java getters).
     * Uses Jackson naming conventions based on config settings.
     */
    private fun getPropertyNames(clazz: Class<*>): Set<String> {
        val names = mutableSetOf<String>()

        // Try Kotlin metadata first - but we need to derive the JSON name from getters
        // since Kotlin property names like "isActive" become "active" in JSON
        val classInfo = scan.getClassInfo(clazz.name)
        val kmClass: KmClass? = classInfo?.kotlinClass()

        // Collect getter-based properties (get* and is* getters)
        // For is* getters, we now also include non-boolean if allowIsGettersForNonBoolean is true
        clazz.methods.filter { method ->
            method.parameterCount == 0 &&
            method.returnType != Void.TYPE &&
            method.declaringClass != Any::class.java &&
            method.declaringClass != Object::class.java &&
            (
                (method.name.startsWith("get") && method.name.length > 3) ||
                (method.name.startsWith("is") && method.name.length > 2)
            )
        }.forEach { getter ->
            val propName = derivePropertyNameFromGetter(getter.name, getter.returnType.name)
            names.add(propName)
        }

        // Also include Kotlin property names that might not have getters
        // (though for data classes they always do)
        if (kmClass != null) {
            kmClass.properties.forEach { prop ->
                // Find the getter for this property to get the correct JSON name
                val getterName = if (prop.name.startsWith("is") && prop.returnType.classifier?.let {
                        (it as? kotlinx.metadata.KmClassifier.Class)?.name
                    } in listOf("kotlin/Boolean", "java/lang/Boolean")) {
                    prop.name // Boolean is* properties use isX() getter
                } else if (prop.name.startsWith("is")) {
                    prop.name // Kotlin generates isX() for all is* properties
                } else {
                    "get${prop.name.replaceFirstChar { it.uppercase() }}"
                }
                // Try to find matching method
                val method = clazz.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
                if (method != null) {
                    val jsonName = derivePropertyNameFromGetter(method.name, method.returnType.name)
                    names.add(jsonName)
                } else {
                    // Fallback to property name
                    names.add(prop.name)
                }
            }
        }

        return names
    }

    private fun extractObjectType(
        clazz: Class<*>,
        nullable: Boolean,
        optional: Boolean,
        typeParams: Map<String, TsType.Inline>
    ): TsType {
        val fqcn = clazz.name
        // Use the name after the package (includes outer class names)
        val simpleName = fqcn.removePrefix(clazz.packageName.plus("."))
        val tsName = config.customNaming(fqcn, simpleName)

        // Check cache to avoid infinite recursion
        val cacheKey = fqcn
        typeCache[cacheKey]?.let {
            return it.inlineReference(nullable = nullable, optional = optional)
        }

        // Pre-register to handle recursive types
        val placeholder = TsType.Object(
            fqcn = fqcn,
            name = if (clazz.typeParameters.isNotEmpty()) {
                "$tsName<${clazz.typeParameters.joinToString(",") { it.name }}>"
            } else tsName,
            nullable = nullable,
            optional = optional
        )
        typeCache[cacheKey] = placeholder

        // Get Kotlin metadata for nullability info
        val classInfo = scan.getClassInfo(fqcn)
        val kmClass: KmClass? = classInfo?.kotlinClass()

        // Collect inherited field names to exclude from this type's own fields
        val inheritedFields = collectInheritedFieldNames(clazz)

        // Extract fields from properties (Kotlin) or getters (Java), excluding inherited ones
        val fields = extractFields(clazz, kmClass, typeParams, inheritedFields)

        // Also extract superclass and interfaces to ensure they're registered
        // Use genericSuperclass/genericInterfaces to preserve parameterization (e.g. IContainer<String, Boolean>)
        val intersections = mutableListOf<TsType>()

        // Extract superclass (if it's not Object/Any)
        val genericSuperclass = clazz.genericSuperclass
        val rawSuperclass = if (genericSuperclass is ParameterizedType) genericSuperclass.rawType as? Class<*> else genericSuperclass as? Class<*>
        if (rawSuperclass != null &&
            rawSuperclass != Any::class.java &&
            rawSuperclass != Object::class.java &&
            config.includeType(rawSuperclass.name)) {
            // Check for self-referential types
            if (genericSuperclass is ParameterizedType) {
                checkSelfReferentialType(clazz, genericSuperclass, "superclass ${rawSuperclass.simpleName}")
            }
            val superType = toTsType(genericSuperclass, false, false, typeParams)
            intersections.add(superType)
        }

        // Extract interfaces with their type arguments
        clazz.genericInterfaces.forEach { genericIface ->
            val rawIface = if (genericIface is ParameterizedType) genericIface.rawType as? Class<*> else genericIface as? Class<*>
            if (rawIface != null && config.includeType(rawIface.name)) {
                // Check for self-referential types
                if (genericIface is ParameterizedType) {
                    checkSelfReferentialType(clazz, genericIface, "interface ${rawIface.simpleName}")
                }
                val ifaceType = toTsType(genericIface, false, false, typeParams)
                intersections.add(ifaceType)
            }
        }

        // After extracting intersections, check if the cache was updated with a better type
        // (e.g., a union child type with discriminator from parent sealed interface extraction)
        val updatedCacheEntry = typeCache[cacheKey]
        if (updatedCacheEntry != null && updatedCacheEntry !== placeholder) {
            // The cache was updated (likely by union extraction triggered through intersection processing)
            // Return the updated type instead of creating a plain Object type
            return updatedCacheEntry.inlineReference(nullable = nullable, optional = optional)
        }

        val result = TsType.Object(
            fqcn = fqcn,
            name = placeholder.name,
            nullable = nullable,
            optional = optional,
            fields = fields,
            generics = if (clazz.typeParameters.isNotEmpty()) {
                clazz.typeParameters.associate { tp ->
                    tp.name to TsType.Inline(fqcn = tp.name, name = tp.name)
                }
            } else emptyMap(),
            intersections = intersections
        )

        typeCache[cacheKey] = result
        return result
    }

    private fun extractFields(
        clazz: Class<*>,
        kmClass: KmClass?,
        typeParams: Map<String, TsType.Inline>,
        excludeFields: Set<String> = emptySet()
    ): Map<String, TsField> {
        val fields = mutableMapOf<String, TsField>()

        // Get ClassGraph ClassInfo for annotation access
        val classInfo = scan.getClassInfo(clazz.name)

        // Check if class has @JsonInclude(NON_NULL) - when true, nullable fields become optional (not null)
        val hasNonNullInclude = jsonAdapter?.hasNonNullInclude(classInfo, clazz) ?: false

        // Map Kotlin property metadata by name
        val kmProperties: Map<String, KmProperty> = kmClass?.properties?.associateBy { it.name } ?: emptyMap()

        // Build a map of constructor parameter annotations by property name
        val ctorParamAnnotations: Map<String, List<io.github.classgraph.AnnotationInfo>> = buildCtorParamAnnotations(classInfo)

        // For Kotlin data classes, use constructor parameters
        if (kmClass != null) {
            val primaryCtor = kmClass.constructors.firstOrNull { !it.isSecondary }
            primaryCtor?.valueParameters?.forEach { param ->
                val propName = param.name

                val isNullable = param.type.isNullable
                val hasDefault = param.declaresDefaultValue

                // Find the corresponding Java field/getter for type info
                val javaType = findPropertyType(clazz, propName) ?: return@forEach

                // Find the getter method to derive the JSON property name
                // Kotlin generates isX() for properties starting with "is", otherwise getX()
                val getterMethod = if (propName.startsWith("is")) {
                    clazz.methods.firstOrNull { it.name == propName && it.parameterCount == 0 }
                } else {
                    val getterName = "get${propName.replaceFirstChar { it.uppercase() }}"
                    clazz.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
                }

                // Derive JSON property name using Jackson naming conventions
                val jsonPropName = if (getterMethod != null) {
                    derivePropertyNameFromGetter(getterMethod.name, getterMethod.returnType.name)
                } else {
                    propName // Fallback to property name if no getter found
                }

                // Skip excluded fields (inherited from superclass) - check both names
                if (jsonPropName in excludeFields || propName in excludeFields) return@forEach

                // Resolve field name via jsonAdapter if available (allows @JsonProperty override)
                val serializedName = resolveSerializedFieldName(classInfo, propName, ctorParamAnnotations)
                // If no annotation override, use JavaBeans-derived name from getter
                // This handles cases like "isActive" -> "active" following Jackson's JavaBeans conventions
                val finalName = if (serializedName == propName) jsonPropName else serializedName

                // Check annotations for optionality from all sources (@field:, @param:, @get:, and bare @)
                // This allows Jackson annotations to override Kotlin's default value detection
                val fieldInfo = classInfo?.fieldInfo?.firstOrNull { it.name == propName }
                val fieldAnnotations = fieldInfo?.annotationInfo?.toList() ?: emptyList()
                val paramAnnotations = ctorParamAnnotations[propName] ?: emptyList()

                // Also check getter method annotations (@get:JsonProperty)
                val getterMethodInfo = if (getterMethod != null && classInfo != null) {
                    classInfo.methodInfo.firstOrNull { it.name == getterMethod.name && it.parameterInfo.isEmpty() }
                } else null
                val getterAnnotations = getterMethodInfo?.annotationInfo?.toList() ?: emptyList()

                // Merge all annotation sources (field, getter, and parameter)
                val combinedAnnotations = fieldAnnotations + getterAnnotations + paramAnnotations
                val jsonAdapterOptional = jsonAdapter?.isOptional(combinedAnnotations)
                val optional = jsonAdapterOptional ?: hasDefault

                // When @JsonInclude(NON_NULL) is set, nullable fields are omitted when null,
                // so they become optional (undefined) in TS, not nullable
                val finalOptional = if (hasNonNullInclude && isNullable) true else optional
                val finalNullable = if (hasNonNullInclude && isNullable) false else isNullable

                val fieldType = toTsType(javaType, finalNullable, false, typeParams)
                fields[finalName] = TsField(
                    type = fieldType,
                    optional = finalOptional,
                    nullable = finalNullable
                )
            }
        }

        // Also include getters (for non-data classes and Java classes)
        // Handle both get* and is* getters (is* now includes non-boolean if configured)
        clazz.methods.filter { method ->
            method.parameterCount == 0 &&
            method.returnType != Void.TYPE &&
            method.declaringClass != Any::class.java &&
            method.declaringClass != Object::class.java &&
            (
                (method.name.startsWith("get") && method.name.length > 3) ||
                (method.name.startsWith("is") && method.name.length > 2)
            )
        }.forEach { getter ->
            // Derive the JSON property name using Jackson naming conventions
            val jsonPropName = derivePropertyNameFromGetter(getter.name, getter.returnType.name)

            // Skip excluded fields (inherited from superclass)
            if (jsonPropName in excludeFields) return@forEach

            // Find the original Kotlin property name (for looking up metadata)
            // For is* getters, the Kotlin property name is the method name
            // For get* getters, the Kotlin property name is decapitalized method name minus "get"
            val kotlinPropName = if (getter.name.startsWith("is")) {
                getter.name
            } else {
                getter.name.substring(3).replaceFirstChar { it.lowercase() }
            }

            // Resolve serialized name (allows @JsonProperty to override)
            val serializedName = resolveSerializedFieldName(classInfo, kotlinPropName, ctorParamAnnotations)
            // Use jsonPropName (JavaBeans naming) if no annotation override, otherwise use serialized name
            val finalName = if (serializedName == kotlinPropName) jsonPropName else serializedName

            if (finalName !in fields) {
                val kmProp = kmProperties[kotlinPropName]
                val isNullable = kmProp?.returnType?.isNullable ?: false
                val javaType = getter.genericReturnType

                // When @JsonInclude(NON_NULL) is set, nullable fields are omitted when null,
                // so they become optional (undefined) in TS, not nullable
                val finalOptional = if (hasNonNullInclude && isNullable) true else false
                val finalNullable = if (hasNonNullInclude && isNullable) false else isNullable

                val fieldType = toTsType(javaType, finalNullable, false, typeParams)
                fields[finalName] = TsField(
                    type = fieldType,
                    optional = finalOptional,
                    nullable = finalNullable
                )
            }
        }

        // Include public fields
        clazz.declaredFields.filter { field ->
            java.lang.reflect.Modifier.isPublic(field.modifiers) &&
            !java.lang.reflect.Modifier.isStatic(field.modifiers)
        }.forEach { field ->
            val propName = field.name
            // Skip excluded fields (inherited from superclass)
            if (propName in excludeFields) return@forEach

            // Resolve serialized name
            val serializedName = resolveSerializedFieldName(classInfo, propName, ctorParamAnnotations)
            if (serializedName !in fields) {
                val kmProp = kmProperties[propName]
                val isNullable = kmProp?.returnType?.isNullable ?: false
                val javaType = field.genericType

                // Check for optionality via jsonAdapter
                val fieldInfo = classInfo?.fieldInfo?.firstOrNull { it.name == propName }
                val annotations = fieldInfo?.annotationInfo?.toList() ?: emptyList()
                val isOptional = jsonAdapter?.isOptional(annotations) ?: false

                // When @JsonInclude(NON_NULL) is set, nullable fields are omitted when null,
                // so they become optional (undefined) in TS, not nullable
                val finalOptional = if (hasNonNullInclude && isNullable) true else isOptional
                val finalNullable = if (hasNonNullInclude && isNullable) false else isNullable

                val fieldType = toTsType(javaType, finalNullable, false, typeParams)
                fields[serializedName] = TsField(
                    type = fieldType,
                    optional = finalOptional,
                    nullable = finalNullable
                )
            }
        }

        return fields
    }

    /**
     * Builds a map of constructor parameter annotations indexed by the original parameter name.
     */
    private fun buildCtorParamAnnotations(classInfo: ClassInfo?): Map<String, List<io.github.classgraph.AnnotationInfo>> {
        if (classInfo == null || jsonAdapter == null) return emptyMap()
        val chosenCtor = jsonAdapter.chooseJsonConstructor(classInfo, scan) ?: return emptyMap()
        val result = mutableMapOf<String, List<io.github.classgraph.AnnotationInfo>>()
        for (param in chosenCtor.parameterInfo) {
            // Use the original parameter name as key, not the renamed one
            val originalName = param.name ?: continue
            result[originalName] = param.annotationInfo?.toList() ?: emptyList()
        }
        return result
    }

    /**
     * Resolves the serialized field name using jsonAdapter if available.
     * Falls back to the original property name if no adapter or no rename.
     */
    private fun resolveSerializedFieldName(
        classInfo: ClassInfo?,
        propName: String,
        ctorParamAnnotations: Map<String, List<io.github.classgraph.AnnotationInfo>>
    ): String {
        if (jsonAdapter == null || classInfo == null) return propName

        // First check constructor parameter annotations for @param:JsonProperty
        val ctorAnns = ctorParamAnnotations[propName] ?: emptyList()
        val ctorRename = jsonAdapter.resolveRenameFromAnnotations(ctorAnns)
        if (ctorRename != null) return ctorRename

        // Try to find field in ClassInfo
        val fieldInfo = classInfo.fieldInfo.firstOrNull { it.name == propName }
        if (fieldInfo != null) {
            val inspection = JsonAdapter.TsFieldInspection.Field(classInfo, fieldInfo, ctorAnns)
            return jsonAdapter.resolveFieldName(classInfo, inspection)
        }

        // Try to find getter
        val getterName = "get${propName.replaceFirstChar { it.uppercase() }}"
        val getterInfo = classInfo.methodInfo.firstOrNull { it.name == getterName && it.parameterInfo.isEmpty() }
        if (getterInfo != null) {
            val inspection = JsonAdapter.TsFieldInspection.Getter(classInfo, getterInfo, ctorAnns)
            return jsonAdapter.resolveFieldName(classInfo, inspection)
        }

        // Fallback
        return propName
    }

    private fun findPropertyType(clazz: Class<*>, propName: String): Type? {
        // Try direct field
        try {
            return clazz.getDeclaredField(propName).genericType
        } catch (_: NoSuchFieldException) {}

        // Try getter
        val getterName = "get${propName.replaceFirstChar { it.uppercase() }}"
        try {
            return clazz.getMethod(getterName).genericReturnType
        } catch (_: NoSuchMethodException) {}

        // Try constructor parameter (best effort)
        clazz.constructors.firstOrNull()?.let { ctor ->
            ctor.parameters.forEachIndexed { idx, param ->
                if (param.name == propName || param.isNamePresent && param.name == propName) {
                    return ctor.genericParameterTypes[idx]
                }
            }
        }

        return null
    }
}
