package com.iodesystems.ts.adapter

import com.fasterxml.jackson.annotation.*
import com.iodesystems.ts.adapter.JsonAdapter.ResolvedDiscriminatedSubTypes
import com.iodesystems.ts.adapter.JsonAdapter.TsFieldInspection
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

        // Find a zero-arg method annotated with @JsonValue
        val jsonValueMethod = clazz.methods.firstOrNull { m ->
            m.getAnnotation(JsonValue::class.java) != null && m.parameterCount == 0
        }
        // Or a field annotated with @JsonValue
        val jsonValueField = if (jsonValueMethod != null) null else clazz.fields.firstOrNull { f ->
            f.getAnnotation(JsonValue::class.java) != null
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
        val jp = an.firstOrNull { it.classInfo.name == JsonProperty::class.java.name } ?: return super.isOptional(an)
        val required = jp.parameterValues.firstOrNull { it.name == "required" }?.value as? Boolean
        val defaultValue = jp.parameterValues.firstOrNull { it.name == "defaultValue" }?.value as? String
        if (required == false) return true
        if (!defaultValue.isNullOrBlank()) return true
        return super.isOptional(an)
    }

    override fun resolveRenameFromAnnotations(annotations: List<io.github.classgraph.AnnotationInfo>): String? {
        fun resolveFromAnnotations(anns: List<io.github.classgraph.AnnotationInfo>): String? {
            val jp = anns.firstOrNull { it.classInfo.name == JsonProperty::class.java.name }
            val explicit = jp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
            if (!explicit.isNullOrBlank()) return explicit

            val ja = anns.firstOrNull { it.classInfo.name == JsonAlias::class.java.name }
            val aliases = ja?.parameterValues?.firstOrNull { it.name == "value" }?.value as? Array<*>
            val firstAlias = aliases?.firstOrNull() as? String
            if (!firstAlias.isNullOrBlank()) return firstAlias
            return null
        }
        return resolveFromAnnotations(annotations)
    }

    override fun resolveFieldName(parent: ClassInfo, inspection: TsFieldInspection): String {
        fun resolveFromAnnotations(anns: List<io.github.classgraph.AnnotationInfo>): String? {
            val jp = anns.firstOrNull { it.classInfo.name == JsonProperty::class.java.name }
            val explicit = jp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
            if (!explicit.isNullOrBlank()) return explicit

            val ja = anns.firstOrNull { it.classInfo.name == JsonAlias::class.java.name }
            val aliases = ja?.parameterValues?.firstOrNull { it.name == "value" }?.value as? Array<*>
            val firstAlias = aliases?.firstOrNull() as? String
            if (!firstAlias.isNullOrBlank()) return firstAlias
            return null
        }
        return when (inspection) {
            is TsFieldInspection.Field -> {
                val fieldAnns = inspection.fi.annotationInfo?.toList() ?: emptyList()
                // Try field annotations first
                resolveFromAnnotations(fieldAnns)
                // Then consider matching constructor parameter annotations (when available)
                    ?: resolveFromAnnotations(inspection.ctorParamAnnotations)
                    // Then consider getter annotations for the same property
                    ?: run {
                        val prop = inspection.fi.name
                        val cap = prop.replaceFirstChar { it.uppercase() }
                        val getter =
                            parent.methodInfo.firstOrNull { it.name == "get$cap" && it.parameterInfo.isEmpty() }
                                ?: parent.methodInfo.firstOrNull { it.name == "is$cap" && it.parameterInfo.isEmpty() }
                        val gAnns = getter?.annotationInfo?.toList() ?: emptyList()
                        resolveFromAnnotations(gAnns)
                    }
                    ?: super.resolveFieldName(parent, inspection)
            }

            is TsFieldInspection.Getter -> {
                val getterAnns = inspection.mi.annotationInfo?.toList() ?: emptyList()
                resolveFromAnnotations(getterAnns)
                    ?: resolveFromAnnotations(inspection.ctorParamAnnotations)
                    ?: super.resolveFieldName(parent, inspection)
            }
        }
    }

    override fun resolveDiscriminatedSubTypes(
        scan: ScanResult,
        clazz: Class<*>,
    ): ResolvedDiscriminatedSubTypes? {
        val jsonTypeInfo = clazz.getAnnotation(JsonTypeInfo::class.java) ?: return null
        val id = jsonTypeInfo.use
        val discriminatorProperty = jsonTypeInfo.property.ifBlank { id.defaultPropertyName }

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
            val discValue = when (id) {
                JsonTypeInfo.Id.CLASS -> implClass.name
                JsonTypeInfo.Id.SIMPLE_NAME -> implClass.simpleName
                JsonTypeInfo.Id.NAME -> {
                    val jtn = implClass.getAnnotation(JsonTypeName::class.java)
                    jtn?.value?.takeIf { it.isNotBlank() } ?: implClass.simpleName
                }

                else -> error("Unsupported type $id")
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
            ctor.annotationInfo.get(JsonCreator::class.java.name) != null
        }?.let { return it }

        // Prefer static method annotated with @JsonCreator
        ci.methodInfo.firstOrNull {
            it.isStatic && it.isPublic && !it.isSynthetic && !it.parameterInfo.isEmpty() && it.annotationInfo.get(
                JsonCreator::class.java.name
            ) != null
        }?.let { return it }

        return super.chooseJsonConstructor(ci, scan)
    }

    override fun nameForConstructorParameter(
        parent: ClassInfo,
        ctor: MethodInfo,
        param: io.github.classgraph.MethodParameterInfo
    ): String? {
        val anns = param.annotationInfo?.toList() ?: emptyList()
        val jp = anns.firstOrNull { it.classInfo.name == JsonProperty::class.java.name }
        val explicit = jp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
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
                val jp = pi.annotationInfo.firstOrNull { it.classInfo.name == JsonProperty::class.java.name }
                val explicit = jp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
                when {
                    !explicit.isNullOrBlank() -> explicit == propName
                    !pi.name.isNullOrBlank() -> pi.name == propName
                    else -> false
                }
            }
            if (paramIndex < 0) return null
            val anns = chosen.parameterInfo[paramIndex].annotationInfo?.toList() ?: emptyList()
            val jp = anns.firstOrNull { it.classInfo.name == JsonProperty::class.java.name }
            val explicit = jp?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
            if (!explicit.isNullOrBlank()) explicit else null
        } catch (_: Throwable) {
            null
        }
    }
}