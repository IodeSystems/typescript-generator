package com.iodesystems.ts.adapter

import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassInfo
import io.github.classgraph.FieldInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.MethodParameterInfo
import io.github.classgraph.ScanResult

// Holds inspection target for a class field-like property
sealed interface TsFieldInspection {
    data class Field(
        val ci: ClassInfo,
        val fi: FieldInfo,
        val ctorParamAnnotations: List<AnnotationInfo> = emptyList()
    ) : TsFieldInspection

    data class Getter(
        val ci: ClassInfo,
        val mi: MethodInfo,
        val ctorParamAnnotations: List<AnnotationInfo> = emptyList()
    ) : TsFieldInspection
}

// Adapter interface to abstract JSON library specific behaviors (e.g., Jackson)
interface JsonAdapter {
    // Resolve the serialized field name for a given inspection target.
    // Default behavior: return the backing name (field name or JavaBean-derived getter property name).
    fun resolveFieldName(parent: ClassInfo, inspection: TsFieldInspection): String {
        return when (inspection) {
            is TsFieldInspection.Field -> inspection.fi.name
            is TsFieldInspection.Getter -> {
                val n = inspection.mi.name
                if (n.startsWith("get") && n.length > 3) {
                    n.substring(3).replaceFirstChar { it.lowercase() }
                } else if (n.startsWith("is") && n.length > 2) {
                    n.substring(2).replaceFirstChar { it.lowercase() }
                } else n
            }
        }
    }

    // Whether the field is considered optional/nullable by JSON rules (default: false)
    fun isFieldNullable(
        fieldAnnotations: List<AnnotationInfo>,
        getterAnnotations: List<AnnotationInfo> = emptyList()
    ): Boolean {
        return (fieldAnnotations + getterAnnotations).firstOrNull {
            it.classInfo.name.endsWith(".Nullable")
        } != null
    }

    // For enums, resolve how values are serialized (e.g., @JsonValue). Return null to use name().
    fun enumSerializedTypeOrNull(enumFqdn: String, enumNames: List<String>): String {
        if (enumNames.isEmpty()) return "never"
        return enumNames.joinToString(" | ") { "'$it'" }
    }

    // Resolve discriminated subtypes for a polymorphic base type.
    // Return null if the adapter cannot determine polymorphic handling for the given base.
    fun resolveDiscriminatedSubTypes(
        scan: ScanResult,
        baseType: ClassInfo,
    ): ResolvedDiscriminatedSubTypes?

    // Optionally allow adapter to pick a JSON constructor for Java classes.
    // Default implementation returns null (no special constructor identified).
    fun chooseJsonConstructor(ci: ClassInfo, scan: ScanResult): MethodInfo? = null

    // Optional fallback: given a property name that wasn't renamed by resolveFieldName, an adapter
    // may still derive a serialized name from constructor parameter annotations (e.g., @JsonProperty).
    // Default: no fallback.
    fun fallbackNameFromCtorParam(scan: ScanResult, ci: ClassInfo, propName: String): String? = null

    // Given an adapter-chosen constructor and one of its parameters, return the serialized key
    // name to associate with this parameter when mapping it to a property. Default behavior uses
    // the bytecode parameter name when available; adapters may override (e.g., @JsonProperty("value")).
    fun nameForConstructorParameter(
        parent: ClassInfo,
        ctor: MethodInfo,
        param: MethodParameterInfo
    ): String? = param.name
}


data class ResolvedDiscriminatedSubTypes(
    val discriminatorProperty: String,
    val options: List<SubtypeOption>
)

data class SubtypeOption(
    val classInfo: ClassInfo,
    val discriminatorValue: String
)

