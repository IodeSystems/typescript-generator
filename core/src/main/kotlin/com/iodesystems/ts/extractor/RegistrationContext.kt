package com.iodesystems.ts.extractor

import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType

/**
 * Tracks type registration and references during extraction.
 */
data class RegistrationContext(
    val jvmExtractor: JvmExtractor,
    val currentApiBaseName: String? = null,
    val currentMethodFqn: String? = null,
) {
    fun resolveAllRequested() {
        // Process any queued types - currently not needed as we resolve eagerly
    }

    fun <T : TsType> addMethodRef(toType: T): T {
        // Record method-level references for graphing
        if (currentMethodFqn != null) {
            addMethodRefRecursive(toType)
        }
        return toType
    }

    private fun addMethodRefRecursive(toType: TsType) {
        if (isGlobalType(toType)) {
            // For global types like Array<T> and Record<K,V>, add refs for their generic types
            if (toType is TsType.Inline) {
                toType.generics.values.forEach { addMethodRefRecursive(it) }
            }
        } else {
            jvmExtractor.references.add(
                TsRef(
                    fromTsBaseName = currentMethodFqn!!,
                    toTsBaseName = toType.name,
                    refType = TsRef.Type.METHOD
                )
            )
            // Also add refs for nested generics
            if (toType is TsType.Inline) {
                toType.generics.values.forEach { addMethodRefRecursive(it) }
            }
        }
    }

    fun registerType(t: TsType?): TsType? {
        if (t == null) return null

        // Don't register global/primitive types themselves, but still process their generics
        if (isGlobalType(t)) {
            // Still need to register generic types inside Array and Record
            if (t is TsType.Inline) {
                t.generics.values.forEach { registerType(it) }
            }
            return t
        }

        // Register the type if it's a complex type (Object, Union, Enum)
        when (t) {
            is TsType.Object -> {
                // Check if already registered to prevent infinite recursion on self-referencing types
                // Use name check because Union and its interface Object share the same fqcn
                val existing = jvmExtractor.types.firstOrNull { it.name == t.name }
                if (existing != null) {
                    // If same fqcn, it's the same type - skip
                    if (existing.fqcn == t.fqcn) {
                        return t
                    }
                    // Different fqcn but same name - this is a collision
                    throw IllegalStateException(
                        "Type alias name collision detected. Multiple JVM types resolve to the same TypeScript name '${t.name}':\n" +
                        "  - ${existing.fqcn}\n" +
                        "  - ${t.fqcn}"
                    )
                }
                jvmExtractor.types.add(t)
                // Recursively register field types
                t.fields.values.forEach { field ->
                    registerType(field.type)
                }
                // Register intersection types (extends/implements) and add type references
                t.intersections.forEach { intersectionType ->
                    registerType(intersectionType)
                    // Add TYPE reference for the inheritance relationship
                    jvmExtractor.references.add(
                        TsRef(
                            fromTsBaseName = t.name,
                            toTsBaseName = intersectionType.name,
                            refType = TsRef.Type.TYPE
                        )
                    )
                }
            }
            is TsType.Union -> {
                // Check by name since Union and its interface have the same fqcn but different names
                val existing = jvmExtractor.types.firstOrNull { it.name == t.name }
                if (existing != null) {
                    // Union types use the same fqcn as their interface, so check the full key
                    if (existing.fqcn == t.fqcn && existing is TsType.Union) {
                        return t
                    }
                    // Different type with same name - this is a collision
                    if (existing.fqcn != t.fqcn) {
                        throw IllegalStateException(
                            "Type alias name collision detected. Multiple JVM types resolve to the same TypeScript name '${t.name}':\n" +
                            "  - ${existing.fqcn}\n" +
                            "  - ${t.fqcn}"
                        )
                    }
                    return t
                }
                jvmExtractor.types.add(t)
                // Also register the interface type (cached as the raw fqcn) via registerType to process its intersections
                val interfaceType = jvmExtractor.typeCache[t.fqcn]
                if (interfaceType != null && interfaceType is TsType.Object) {
                    registerType(interfaceType)
                }
                t.children.forEach { registerType(it) }
            }
            is TsType.Enum -> {
                if (jvmExtractor.types.none { it.fqcn == t.fqcn }) {
                    jvmExtractor.types.add(t)
                }
            }
            is TsType.Inline -> {
                // For inline references, check if we have a cached full type
                val cachedType = jvmExtractor.typeCache[t.fqcn]
                if (cachedType != null && cachedType !is TsType.Inline) {
                    registerType(cachedType)
                }
                // Also check for Union type (cached with :Union suffix)
                val cachedUnion = jvmExtractor.typeCache["${t.fqcn}:Union"]
                if (cachedUnion != null && cachedUnion is TsType.Union) {
                    registerType(cachedUnion)
                }
                // Also register the generic types
                t.generics.values.forEach { registerType(it) }
            }
            is TsType.Alias -> {
                // Register the alias type if not already registered
                if (jvmExtractor.types.none { it.fqcn == t.fqcn }) {
                    jvmExtractor.types.add(t)
                }
            }
        }

        return t
    }

    private fun isGlobalType(t: TsType): Boolean {
        return t.name in listOf("string", "number", "boolean", "void", "any") ||
               t.name.startsWith("Array<") ||
               t.name.startsWith("Record<")
    }
}
