package com.iodesystems.ts.lib

import io.github.classgraph.*
import org.springframework.core.annotation.AnnotatedElementUtils
import kotlin.reflect.KClass

/**
 * Utility for annotation access that handles classloader isolation issues.
 *
 * In Gradle plugin context, annotation classes from the plugin's classloader differ from
 * those on the project classpath. This utility resolves annotations by name (string comparison)
 * instead of class identity, avoiding classloader mismatch issues.
 *
 * Usage:
 * ```kotlin
 * // Check if annotation is present
 * if (AnnotationUtils.hasAnnotation(clazz, JsonTypeInfo::class)) { ... }
 *
 * // Get annotation values
 * val ann = AnnotationUtils.getAnnotation(clazz, JsonTypeInfo::class)
 * val useValue = ann?.getString("use")
 * ```
 */
object AnnotationUtils {

    /**
     * Sealed interface representing typed annotation parameter values.
     * Handles all possible annotation value types with proper type safety.
     */
    sealed interface AnnotationValue {
        /** String value */
        data class StringValue(val value: String) : AnnotationValue

        /** Boolean value */
        data class BooleanValue(val value: Boolean) : AnnotationValue

        /** Integer value (includes Byte, Short, Int) */
        data class IntValue(val value: Int) : AnnotationValue

        /** Long value */
        data class LongValue(val value: Long) : AnnotationValue

        /** Float value */
        data class FloatValue(val value: Float) : AnnotationValue

        /** Double value */
        data class DoubleValue(val value: Double) : AnnotationValue

        /** Char value */
        data class CharValue(val value: Char) : AnnotationValue

        /** Enum constant (stored as its name) */
        data class EnumValue(val enumName: String) : AnnotationValue

        /** Class reference (stored as fully qualified name) */
        data class ClassValue(val className: String) : AnnotationValue

        /** Array of annotation values */
        data class ArrayValue(val values: List<AnnotationValue>) : AnnotationValue

        /** Nested annotation */
        data class NestedAnnotation(val annotation: AnnotationValues) : AnnotationValue

        /** Null value */
        data object NullValue : AnnotationValue

        /** Get value as String, converting if needed */
        fun asString(): String? = when (this) {
            is StringValue -> value
            is EnumValue -> enumName
            is ClassValue -> className
            is BooleanValue -> value.toString()
            is IntValue -> value.toString()
            is LongValue -> value.toString()
            is FloatValue -> value.toString()
            is DoubleValue -> value.toString()
            is CharValue -> value.toString()
            is ArrayValue -> values.firstOrNull()?.asString()
            is NestedAnnotation -> null
            is NullValue -> null
        }

        /** Get value as Boolean */
        fun asBoolean(): Boolean? = when (this) {
            is BooleanValue -> value
            is StringValue -> value.toBooleanStrictOrNull()
            else -> null
        }

        /** Get value as Int */
        fun asInt(): Int? = when (this) {
            is IntValue -> value
            is LongValue -> value.toInt()
            is StringValue -> value.toIntOrNull()
            else -> null
        }

        /** Get value as Long */
        fun asLong(): Long? = when (this) {
            is LongValue -> value
            is IntValue -> value.toLong()
            is StringValue -> value.toLongOrNull()
            else -> null
        }

        /** Get value as List of strings (for array values) */
        fun asStringList(): List<String>? = when (this) {
            is ArrayValue -> values.mapNotNull { it.asString() }
            is StringValue -> listOf(value)
            else -> null
        }
    }

    /**
     * Result of annotation lookup containing parameter values.
     */
    data class AnnotationValues(
        val annotationName: String,
        val values: Map<String, AnnotationValue>
    ) {
        /** Get raw AnnotationValue for a key */
        operator fun get(key: String): AnnotationValue? = values[key]

        /** Get value as String */
        fun getString(key: String): String? = values[key]?.asString()

        /** Get value as Boolean */
        fun getBoolean(key: String): Boolean? = values[key]?.asBoolean()

        /** Get value as Int */
        fun getInt(key: String): Int? = values[key]?.asInt()

        /** Get value as Long */
        fun getLong(key: String): Long? = values[key]?.asLong()

        /** Get value as list of strings */
        fun getStringList(key: String): List<String>? = values[key]?.asStringList()

        /** Get nested annotation */
        fun getAnnotation(key: String): AnnotationValues? =
            (values[key] as? AnnotationValue.NestedAnnotation)?.annotation
    }

    // ========== List<AnnotationInfo> lookups (common in JsonAdapter methods) ==========

    /**
     * Get annotation from a list of ClassGraph AnnotationInfo by annotation class.
     */
    fun getAnnotation(annotations: List<AnnotationInfo>?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(annotations, annotationClass.java.name)
    }

    /**
     * Get annotation from a list of ClassGraph AnnotationInfo by annotation class name.
     */
    fun getAnnotation(annotations: List<AnnotationInfo>?, annotationClassName: String): AnnotationValues? {
        val ann = annotations?.firstOrNull { it.name == annotationClassName } ?: return null
        return extractValues(ann)
    }

    /**
     * Check if annotation list contains an annotation.
     */
    fun hasAnnotation(annotations: List<AnnotationInfo>?, annotationClass: KClass<out Annotation>): Boolean {
        return annotations?.any { it.name == annotationClass.java.name } == true
    }

    // ========== ClassGraph-based lookups ==========

    /**
     * Get annotation from ClassGraph ClassInfo by annotation class.
     */
    fun getAnnotation(classInfo: ClassInfo?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(classInfo, annotationClass.java.name)
    }

    /**
     * Get annotation from ClassGraph ClassInfo by annotation class name.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun getAnnotation(classInfo: ClassInfo?, annotationClassName: String): AnnotationValues? {
        val ann = classInfo?.annotationInfo?.firstOrNull { it.name == annotationClassName } ?: return null
        return extractValues(ann)
    }

    /**
     * Get annotation from ClassGraph MethodInfo by annotation class.
     */
    fun getAnnotation(methodInfo: MethodInfo?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(methodInfo, annotationClass.java.name)
    }

    /**
     * Get annotation from ClassGraph MethodInfo by annotation class name.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun getAnnotation(methodInfo: MethodInfo?, annotationClassName: String): AnnotationValues? {
        val ann = methodInfo?.annotationInfo?.firstOrNull { it.name == annotationClassName } ?: return null
        return extractValues(ann)
    }

    /**
     * Get annotation from ClassGraph FieldInfo by annotation class.
     */
    fun getAnnotation(fieldInfo: FieldInfo?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(fieldInfo, annotationClass.java.name)
    }

    /**
     * Get annotation from ClassGraph FieldInfo by annotation class name.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun getAnnotation(fieldInfo: FieldInfo?, annotationClassName: String): AnnotationValues? {
        val ann = fieldInfo?.annotationInfo?.firstOrNull { it.name == annotationClassName } ?: return null
        return extractValues(ann)
    }

    /**
     * Get annotation from ClassGraph MethodParameterInfo by annotation class.
     */
    fun getAnnotation(paramInfo: MethodParameterInfo?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(paramInfo, annotationClass.java.name)
    }

    /**
     * Get annotation from ClassGraph MethodParameterInfo by annotation class name.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun getAnnotation(paramInfo: MethodParameterInfo?, annotationClassName: String): AnnotationValues? {
        val ann = paramInfo?.annotationInfo?.firstOrNull { it.name == annotationClassName } ?: return null
        return extractValues(ann)
    }

    /**
     * Check if ClassGraph ClassInfo has annotation.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun hasAnnotation(classInfo: ClassInfo?, annotationClass: KClass<out Annotation>): Boolean {
        return classInfo?.annotationInfo?.any { it.name == annotationClass.java.name } == true
    }

    /**
     * Check if ClassGraph MethodInfo has annotation.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun hasAnnotation(methodInfo: MethodInfo?, annotationClass: KClass<out Annotation>): Boolean {
        return methodInfo?.annotationInfo?.any { it.name == annotationClass.java.name } == true
    }

    /**
     * Check if ClassGraph MethodParameterInfo has annotation.
     * Note: We iterate manually because Kotlin may resolve .get() to ArrayList.get(int)
     * instead of AnnotationInfoList.get(String).
     */
    fun hasAnnotation(paramInfo: MethodParameterInfo?, annotationClass: KClass<out Annotation>): Boolean {
        return paramInfo?.annotationInfo?.any { it.name == annotationClass.java.name } == true
    }

    // ========== Java reflection-based lookups (uses Spring merged annotations for @AliasFor support) ==========

    /**
     * Loads an annotation class from the target's classloader to handle classloader isolation.
     * This is critical for Gradle plugin context where the plugin and project have different classloaders.
     */
    private fun loadAnnotationClass(targetClassLoader: ClassLoader?, annotationClass: KClass<out Annotation>): Class<out Annotation>? {
        return loadAnnotationClass(targetClassLoader, annotationClass.java.name)
    }

    private fun loadAnnotationClass(targetClassLoader: ClassLoader?, annotationClassName: String): Class<out Annotation>? {
        return try {
            @Suppress("UNCHECKED_CAST")
            targetClassLoader?.loadClass(annotationClassName) as? Class<out Annotation>
                ?: Class.forName(annotationClassName) as? Class<out Annotation>
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get annotation from Java Class by annotation class.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun getAnnotation(clazz: Class<*>?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        if (clazz == null) return null
        // Load annotation class from target's classloader to handle Gradle plugin classloader isolation
        val annClass = loadAnnotationClass(clazz.classLoader, annotationClass) ?: return null
        val ann = AnnotatedElementUtils.findMergedAnnotation(clazz, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Get annotation from Java Class by annotation class name.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     */
    fun getAnnotation(clazz: Class<*>?, annotationClassName: String): AnnotationValues? {
        if (clazz == null) return null
        val annClass = loadAnnotationClass(clazz.classLoader, annotationClassName)
        if (annClass == null) {
            // Fallback to direct lookup if annotation class can't be loaded
            val ann = clazz.annotations.firstOrNull { it.annotationClass.java.name == annotationClassName }
                ?: return null
            return extractValuesFromReflection(ann)
        }
        val ann = AnnotatedElementUtils.findMergedAnnotation(clazz, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Get annotation from Java Method by annotation class.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun getAnnotation(method: java.lang.reflect.Method?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        if (method == null) return null
        val annClass = loadAnnotationClass(method.declaringClass.classLoader, annotationClass) ?: return null
        val ann = AnnotatedElementUtils.findMergedAnnotation(method, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Get annotation from Java Method by annotation class name.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     */
    fun getAnnotation(method: java.lang.reflect.Method?, annotationClassName: String): AnnotationValues? {
        if (method == null) return null
        val annClass = loadAnnotationClass(method.declaringClass.classLoader, annotationClassName)
        if (annClass == null) {
            // Fallback to direct lookup if annotation class can't be loaded
            val ann = method.annotations.firstOrNull { it.annotationClass.java.name == annotationClassName }
                ?: return null
            return extractValuesFromReflection(ann)
        }
        val ann = AnnotatedElementUtils.findMergedAnnotation(method, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Get annotation from Java Field by annotation class.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun getAnnotation(field: java.lang.reflect.Field?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        if (field == null) return null
        val annClass = loadAnnotationClass(field.declaringClass.classLoader, annotationClass) ?: return null
        val ann = AnnotatedElementUtils.findMergedAnnotation(field, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Get annotation from Java Field by annotation class name.
     * Uses Spring's merged annotation support to resolve @AliasFor meta-annotations.
     */
    fun getAnnotation(field: java.lang.reflect.Field?, annotationClassName: String): AnnotationValues? {
        if (field == null) return null
        val annClass = loadAnnotationClass(field.declaringClass.classLoader, annotationClassName)
        if (annClass == null) {
            // Fallback to direct lookup if annotation class can't be loaded
            val ann = field.annotations.firstOrNull { it.annotationClass.java.name == annotationClassName }
                ?: return null
            return extractValuesFromReflection(ann)
        }
        val ann = AnnotatedElementUtils.findMergedAnnotation(field, annClass) ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Check if Java Class has annotation.
     * Uses Spring's merged annotation support to find annotations via meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun hasAnnotation(clazz: Class<*>?, annotationClass: KClass<out Annotation>): Boolean {
        if (clazz == null) return false
        val annClass = loadAnnotationClass(clazz.classLoader, annotationClass) ?: return false
        return AnnotatedElementUtils.findMergedAnnotation(clazz, annClass) != null
    }

    /**
     * Check if Java Class has annotation DIRECTLY (not inherited from superclass/interface).
     * Uses Spring's merged annotation support for @AliasFor but does not follow type hierarchy.
     * Use this when you need to distinguish between direct and inherited annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun hasDirectAnnotation(clazz: Class<*>?, annotationClass: KClass<out Annotation>): Boolean {
        if (clazz == null) return false
        val targetAnnClass = loadAnnotationClass(clazz.classLoader, annotationClass) ?: return false
        // Check annotations directly declared on this class (not inherited)
        return clazz.declaredAnnotations.any { ann ->
            // Check for direct match or meta-annotation via Spring's support
            ann.annotationClass.java.name == targetAnnClass.name ||
                AnnotatedElementUtils.findMergedAnnotation(ann.annotationClass.java, targetAnnClass) != null
        }
    }

    /**
     * Check if Java Method has annotation.
     * Uses Spring's merged annotation support to find annotations via meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun hasAnnotation(method: java.lang.reflect.Method?, annotationClass: KClass<out Annotation>): Boolean {
        if (method == null) return false
        val annClass = loadAnnotationClass(method.declaringClass.classLoader, annotationClass) ?: return false
        return AnnotatedElementUtils.findMergedAnnotation(method, annClass) != null
    }

    /**
     * Check if Java Field has annotation.
     * Uses Spring's merged annotation support to find annotations via meta-annotations.
     * Handles classloader isolation by loading the annotation class from the target's classloader.
     */
    fun hasAnnotation(field: java.lang.reflect.Field?, annotationClass: KClass<out Annotation>): Boolean {
        if (field == null) return false
        val annClass = loadAnnotationClass(field.declaringClass.classLoader, annotationClass) ?: return false
        return AnnotatedElementUtils.findMergedAnnotation(field, annClass) != null
    }

    /**
     * Get annotation from Java Parameter by annotation class.
     * Uses name-based lookup to avoid classloader isolation issues.
     */
    fun getAnnotation(param: java.lang.reflect.Parameter?, annotationClass: KClass<out Annotation>): AnnotationValues? {
        return getAnnotation(param, annotationClass.java.name)
    }

    /**
     * Get annotation from Java Parameter by annotation class name.
     * Uses name-based lookup to avoid classloader isolation issues.
     */
    fun getAnnotation(param: java.lang.reflect.Parameter?, annotationClassName: String): AnnotationValues? {
        if (param == null) return null
        val ann = param.annotations.firstOrNull { it.annotationClass.java.name == annotationClassName }
            ?: return null
        return extractValuesFromReflection(ann)
    }

    /**
     * Check if Java Parameter has annotation (by name, handles classloader isolation).
     */
    fun hasAnnotation(param: java.lang.reflect.Parameter?, annotationClass: KClass<out Annotation>): Boolean {
        return param?.annotations?.any { it.annotationClass.java.name == annotationClass.java.name } == true
    }

    // ========== Combined lookup (tries ClassGraph first, then reflection) ==========

    /**
     * Get annotation trying ClassGraph first, then falling back to reflection.
     * This is the preferred method when you have both ClassInfo and Class available.
     */
    fun getAnnotation(
        classInfo: ClassInfo?,
        clazz: Class<*>?,
        annotationClass: KClass<out Annotation>
    ): AnnotationValues? {
        return getAnnotation(classInfo, annotationClass)
            ?: getAnnotation(clazz, annotationClass)
    }

    // ========== Private helpers ==========

    private fun extractValues(ann: AnnotationInfo): AnnotationValues {
        val values = mutableMapOf<String, AnnotationValue>()
        for (param in ann.parameterValues) {
            values[param.name] = toAnnotationValue(param.value)
        }
        return AnnotationValues(ann.name, values)
    }

    private fun extractValuesFromReflection(ann: Annotation): AnnotationValues {
        val annClass = ann.annotationClass.java
        val values = mutableMapOf<String, AnnotationValue>()

        for (method in annClass.declaredMethods) {
            if (method.parameterCount == 0) {
                try {
                    val value = method.invoke(ann)
                    values[method.name] = toAnnotationValue(value)
                } catch (_: Exception) {
                    // Skip methods that fail (e.g., default methods)
                }
            }
        }
        return AnnotationValues(annClass.name, values)
    }

    private fun toAnnotationValue(value: Any?): AnnotationValue {
        return when (value) {
            null -> AnnotationValue.NullValue
            // ClassGraph types
            is AnnotationEnumValue -> AnnotationValue.EnumValue(value.valueName)
            is AnnotationClassRef -> AnnotationValue.ClassValue(value.name)
            is AnnotationInfo -> AnnotationValue.NestedAnnotation(extractValues(value))
            // Java reflection types
            is Enum<*> -> AnnotationValue.EnumValue(value.name)
            is Class<*> -> AnnotationValue.ClassValue(value.name)
            is Annotation -> AnnotationValue.NestedAnnotation(extractValuesFromReflection(value))
            // Primitives
            is String -> AnnotationValue.StringValue(value)
            is Boolean -> AnnotationValue.BooleanValue(value)
            is Char -> AnnotationValue.CharValue(value)
            is Byte -> AnnotationValue.IntValue(value.toInt())
            is Short -> AnnotationValue.IntValue(value.toInt())
            is Int -> AnnotationValue.IntValue(value)
            is Long -> AnnotationValue.LongValue(value)
            is Float -> AnnotationValue.FloatValue(value)
            is Double -> AnnotationValue.DoubleValue(value)
            // Arrays
            is Array<*> -> AnnotationValue.ArrayValue(value.map { toAnnotationValue(it) })
            is BooleanArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.BooleanValue(it) })
            is ByteArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.IntValue(it.toInt()) })
            is ShortArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.IntValue(it.toInt()) })
            is IntArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.IntValue(it) })
            is LongArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.LongValue(it) })
            is FloatArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.FloatValue(it) })
            is DoubleArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.DoubleValue(it) })
            is CharArray -> AnnotationValue.ArrayValue(value.map { AnnotationValue.CharValue(it) })
            // Fallback - convert to string
            else -> AnnotationValue.StringValue(value.toString())
        }
    }
}
