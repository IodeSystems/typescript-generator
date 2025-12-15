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
        // Use AnnotationUtils for classloader-safe annotation lookup
        val classInfo = scan.getClassInfo(clazz.name)
        val jsonTypeInfoAnn = AnnotationUtils.getAnnotation(classInfo, clazz, JsonTypeInfo::class)
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

        // First try ClassGraph scan for implementations (works for classes within scanned packages)
        var implClasses = scan.getClassesImplementing(clazz.name)
            .sortedBy { it.simpleName }
            .map { it.loadClass() }

        // If ClassGraph didn't find any, use Java reflection for sealed classes/interfaces
        // This handles types from external packages that aren't in the scan path
        if (implClasses.isEmpty()) {
            implClasses = try {
                clazz.permittedSubclasses?.map { it }?.sortedBy { it.simpleName } ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
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
}