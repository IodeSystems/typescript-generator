package com.iodesystems.ts.adapter

import com.fasterxml.jackson.annotation.*
import com.iodesystems.ts.adapter.JsonAdapter.ResolvedDiscriminatedSubTypes
import com.iodesystems.ts.adapter.JsonAdapter.TsFieldInspection
import com.iodesystems.ts.lib.AnnotationUtils
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult

// Default no-op Jackson adapter (baseline behavior remains unchanged for now)
class JacksonJsonAdapter : JsonAdapter {
    override fun enumSerializedTypeOrNull(scan: ScanResult, enumFqdn: String, enumNames: List<String>): String {
        // Attempt to honor @JsonValue on enum fields or methods by reflectively loading the class
        // and computing the serialized values for each constant. Fallback to default behavior
        // (the enum constant names) if anything is missing or fails.

        // Try to load the class - it might not be in the scanned packages but still on the classpath
        val clazz = try {
            scan.getClassInfo(enumFqdn)?.loadClass()
                ?: Class.forName(enumFqdn)
        } catch (_: Exception) {
            return super.enumSerializedTypeOrNull(scan, enumFqdn, enumNames)
        }
        if (!clazz.isEnum) return super.enumSerializedTypeOrNull(scan, enumFqdn, enumNames)

        // Find a zero-arg method annotated with @JsonValue (using name-based lookup for classloader isolation)
        val jsonValueMethod = clazz.methods.firstOrNull { m ->
            AnnotationUtils.hasAnnotation(m, JsonValue::class) && m.parameterCount == 0
        }
        // Or a field annotated with @JsonValue
        val jsonValueField = if (jsonValueMethod != null) null else clazz.fields.firstOrNull { f ->
            AnnotationUtils.hasAnnotation(f, JsonValue::class)
        }

        if (jsonValueMethod == null && jsonValueField == null) {
            return super.enumSerializedTypeOrNull(scan, enumFqdn, enumNames)
        }

        @Suppress("UNCHECKED_CAST")
        val constants = clazz.enumConstants as Array<Any>
        val values: List<Any?> = when {
            jsonValueMethod != null -> {
                constants.map { c -> jsonValueMethod.invoke(c) }
            }

            jsonValueField != null -> {
                constants.map { c -> jsonValueField.get(c) }
            }

            else -> emptyList()
        }

        if (values.isEmpty()) return super.enumSerializedTypeOrNull(scan, enumFqdn, enumNames)

        // Format as TypeScript union literals, preserving primitive types
        fun tsLiteral(v: Any?): String = when (v) {
            null -> "null" // unlikely for @JsonValue but handle defensively
            is String -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'"
            is Char -> "'" + v.toString().replace("'", "\\'") + "'"
            is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double -> v.toString()
            else -> "'" + v.toString().replace("\\", "\\\\").replace("'", "\\'") + "'"
        }

        return values.joinToString(" | ") { tsLiteral(it) }
    }

    override fun isOptional(an: List<io.github.classgraph.AnnotationInfo>): Boolean? {
        val jp = AnnotationUtils.getAnnotation(an, JsonProperty::class) ?: return super.isOptional(an)
        val required = jp.getBoolean("required")
        val defaultValue = jp.getString("defaultValue")
        if (required == false) return true
        if (!defaultValue.isNullOrBlank()) return true
        return super.isOptional(an)
    }

    override fun resolveRenameFromAnnotations(annotations: List<io.github.classgraph.AnnotationInfo>): String? {
        return resolveNameFromAnnotations(annotations)
    }

    /**
     * Extract field name from @JsonProperty or @JsonAlias annotations.
     */
    private fun resolveNameFromAnnotations(anns: List<io.github.classgraph.AnnotationInfo>): String? {
        val jp = AnnotationUtils.getAnnotation(anns, JsonProperty::class)
        val explicit = jp?.getString("value")
        if (!explicit.isNullOrBlank()) return explicit

        val ja = AnnotationUtils.getAnnotation(anns, JsonAlias::class)
        val firstAlias = ja?.getStringList("value")?.firstOrNull()
        if (!firstAlias.isNullOrBlank()) return firstAlias
        return null
    }

    override fun resolveFieldName(parent: ClassInfo, inspection: TsFieldInspection): String {
        return when (inspection) {
            is TsFieldInspection.Field -> {
                val fieldAnns = inspection.fi.annotationInfo?.toList() ?: emptyList()
                // Try field annotations first
                resolveNameFromAnnotations(fieldAnns)
                // Then consider matching constructor parameter annotations (when available)
                    ?: resolveNameFromAnnotations(inspection.ctorParamAnnotations)
                    // Then consider getter annotations for the same property
                    ?: run {
                        val prop = inspection.fi.name
                        val cap = prop.replaceFirstChar { it.uppercase() }
                        val getter =
                            parent.methodInfo.firstOrNull { it.name == "get$cap" && it.parameterInfo.isEmpty() }
                                ?: parent.methodInfo.firstOrNull { it.name == "is$cap" && it.parameterInfo.isEmpty() }
                        val gAnns = getter?.annotationInfo?.toList() ?: emptyList()
                        resolveNameFromAnnotations(gAnns)
                    }
                    ?: super.resolveFieldName(parent, inspection)
            }

            is TsFieldInspection.Getter -> {
                val getterAnns = inspection.mi.annotationInfo?.toList() ?: emptyList()
                resolveNameFromAnnotations(getterAnns)
                    ?: resolveNameFromAnnotations(inspection.ctorParamAnnotations)
                    ?: super.resolveFieldName(parent, inspection)
            }
        }
    }

    override fun resolveDiscriminatedSubTypes(
        scan: ScanResult,
        clazz: Class<*>,
    ): ResolvedDiscriminatedSubTypes? {
        // Only treat a class as a union root if it DIRECTLY has @JsonTypeInfo
        // (or a meta-annotation aliasing it like @JsonUnion).
        // Inherited @JsonTypeInfo (e.g., Ref.Loc inheriting from Ref) should not
        // cause a new union to be created - such classes are union MEMBERS, not roots.
        if (!AnnotationUtils.hasDirectAnnotation(clazz, JsonTypeInfo::class)) {
            return null
        }
        // Now get the annotation values (can use merged since we know it's directly present)
        val jsonTypeInfoAnn = AnnotationUtils.getAnnotation(clazz, JsonTypeInfo::class)
            ?: return null

        val id = jsonTypeInfoAnn.getString("use") ?: "CLASS"
        val discriminatorProperty = (jsonTypeInfoAnn.getString("property") ?: "").ifBlank {
            when (id) {
                "CLASS" -> "@class"
                "SIMPLE_NAME" -> "@type"
                "NAME" -> "@type"
                "MINIMAL_CLASS" -> "@c"
                else -> "@type"
            }
        }

        // For sealed classes/interfaces, find all subtypes within the sealed hierarchy.
        // We need to recursively traverse permittedSubclasses because nested classes may
        // extend each other (e.g., Ref.Bu extends Ref.Org, not Ref directly).
        // BUT we must exclude classes that also implement another @JsonTypeInfo sealed type
        // (e.g., SlugRef.Org implements both Ref and SlugRef - it should only appear in SlugRef's union).
        val implClasses = collectSealedHierarchy(clazz, scan).sortedBy { it.simpleName }
            .ifEmpty {
                // Fall back to ClassGraph scan for non-sealed types
                scan.getClassesImplementing(clazz.name)
                    .sortedBy { it.simpleName }
                    .map { it.loadClass() }
            }

        val options = implClasses.map { implClass ->
            val implClassInfo = scan.getClassInfo(implClass.name)
            val discValue = when (id) {
                "CLASS" -> implClass.name
                "SIMPLE_NAME" -> implClass.simpleName
                "NAME" -> {
                    // Use AnnotationUtils for classloader-safe @JsonTypeName lookup
                    val jtnAnn = AnnotationUtils.getAnnotation(implClassInfo, implClass, JsonTypeName::class)
                    jtnAnn?.getString("value")?.takeIf { it.isNotBlank() } ?: implClass.simpleName
                }

                else -> error("Unsupported JsonTypeInfo.Id type: $id")
            }
            JsonAdapter.SubtypeOption(implClass, discValue)
        }
        return ResolvedDiscriminatedSubTypes(discriminatorProperty, options)
    }

    override fun chooseJsonConstructor(ci: ClassInfo, scan: ScanResult): MethodInfo? {
        val ctors = ci.constructorInfo
            .filter { it.isPublic && !it.isSynthetic }
        // Prefer a constructor annotated with @JsonCreator
        ctors.firstOrNull { ctor ->
            AnnotationUtils.hasAnnotation(ctor, JsonCreator::class)
        }?.let { return it }

        // Prefer static method annotated with @JsonCreator
        ci.methodInfo.firstOrNull {
            it.isStatic && it.isPublic && !it.isSynthetic && !it.parameterInfo.isEmpty() &&
                AnnotationUtils.hasAnnotation(it, JsonCreator::class)
        }?.let { return it }

        return super.chooseJsonConstructor(ci, scan)
    }

    override fun nameForConstructorParameter(
        parent: ClassInfo,
        ctor: MethodInfo,
        param: io.github.classgraph.MethodParameterInfo
    ): String? {
        val anns = param.annotationInfo?.toList() ?: emptyList()
        val jp = AnnotationUtils.getAnnotation(anns, JsonProperty::class)
        val explicit = jp?.getString("value")
        return when {
            !explicit.isNullOrBlank() -> explicit
            !param.name.isNullOrBlank() -> param.name
            else -> null
        }
    }

    override fun fallbackNameFromCtorParam(
        scan: ScanResult,
        ci: ClassInfo,
        propName: String
    ): String? {
        return try {
            // Kotlin path: not needed here because JvmExtractor already passes ctorParamAnnotations
            // for Kotlin constructor parameters into resolveFieldName(). Keep fallback only for Java.
            val chosen = chooseJsonConstructor(ci, scan) ?: return null
            val paramIndex = chosen.parameterInfo.indexOfFirst { pi ->
                val jp = AnnotationUtils.getAnnotation(pi, JsonProperty::class)
                val explicit = jp?.getString("value")
                when {
                    !explicit.isNullOrBlank() -> explicit == propName
                    !pi.name.isNullOrBlank() -> pi.name == propName
                    else -> false
                }
            }
            if (paramIndex < 0) return null
            val anns = chosen.parameterInfo[paramIndex].annotationInfo?.toList() ?: emptyList()
            val jp = AnnotationUtils.getAnnotation(anns, JsonProperty::class)
            val explicit = jp?.getString("value")
            if (!explicit.isNullOrBlank()) explicit else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Collects all classes in a sealed hierarchy, starting from the root sealed class/interface.
     *
     * This handles cases where sealed subtypes extend each other (e.g., Ref.Bu extends Ref.Org)
     * by recursively traversing the hierarchy. It also filters out classes that implement
     * another @JsonTypeInfo sealed type (e.g., SlugRef.Org implements both Ref and SlugRef -
     * it should only appear in SlugRef's union, not Ref's).
     */
    private fun collectSealedHierarchy(rootClass: Class<*>, scan: ScanResult): List<Class<*>> {
        val result = mutableListOf<Class<*>>()
        val visited = mutableSetOf<Class<*>>()

        // Collect all nested classes of the root class for reflection-based subclass lookup
        // This is needed when the classes aren't in ClassGraph's scan path
        val allNestedClasses: Set<Class<*>> = try {
            rootClass.declaredClasses.toSet()
        } catch (_: Throwable) {
            emptySet()
        }

        fun shouldSkipClass(clazz: Class<*>): Boolean {
            // Check if this class also implements another @JsonTypeInfo sealed type
            // that is not our root class. If so, skip it - it belongs to that other hierarchy.
            // Use hasDirectAnnotation to check only DIRECT annotations on the super type,
            // not annotations inherited through the type hierarchy (which would incorrectly
            // filter out classes like Ref.Bu when checking Ref.Org -> Ref).
            val superTypes = clazz.interfaces.toList() + listOfNotNull(clazz.superclass)
            return superTypes
                .filter { superType -> superType != rootClass && superType != Any::class.java && superType != Object::class.java }
                .any { superType -> AnnotationUtils.hasDirectAnnotation(superType, JsonTypeInfo::class) }
        }

        fun findReflectionSubclasses(parentClass: Class<*>): List<Class<*>> {
            // Find nested classes that extend this parent class
            // This handles cases where ClassGraph didn't scan the package
            return allNestedClasses
                .filter { nested -> nested.superclass == parentClass }
                .filter { nested -> nested !in visited }
        }

        fun collectRecursive(clazz: Class<*>) {
            // Get direct permitted subclasses (for sealed classes/interfaces)
            val permitted: List<Class<*>> = try {
                clazz.permittedSubclasses?.toList() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }

            // For non-sealed classes (like open class Ref.Org), find subclasses
            // via ClassGraph first, then fall back to reflection
            val subclassesFromGraph: List<Class<*>> = scan.getSubclasses(clazz.name)
                .map { classInfo -> classInfo.loadClass() }
                .filter { cls -> cls !in visited }

            // If ClassGraph didn't find any (package not scanned), use reflection
            val subclassesFromReflection = if (subclassesFromGraph.isEmpty()) {
                findReflectionSubclasses(clazz)
            } else emptyList()

            // Process all subclasses: permitted (for sealed types) + discovered (for open types)
            val allSubclasses = (permitted + subclassesFromGraph + subclassesFromReflection).distinct()

            for (subclass in allSubclasses) {
                if (subclass in visited) continue
                visited.add(subclass)

                if (shouldSkipClass(subclass)) continue

                result.add(subclass)

                // Recursively collect subclasses of this class
                collectRecursive(subclass)
            }
        }

        collectRecursive(rootClass)
        return result
    }
}