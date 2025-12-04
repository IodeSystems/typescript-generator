package com.iodesystems.ts.adapter

import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import io.github.classgraph.*
import kotlinx.metadata.KmType
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.isNullable

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

    fun isNullable(s: HierarchicalTypeSignature, k: KmType?, an: List<AnnotationInfo>): Boolean? {
        isNullable(an)?.let { return it }
        if (k !== null) return k.isNullable
        return null
    }

    fun isOptional(
        k: KmType?,
        kvp: KmValueParameter?,
        an: List<AnnotationInfo>
    ): Boolean? {
        isOptional(an)?.let { return it }
        if (kvp != null) return kvp.declaresDefaultValue
        return null
    }

    data class ResolvedFieldInfo(
        val name: String,
        val type: HierarchicalTypeSignature,
        val optional: Boolean?,
        val nullable: Boolean?,
    )

    fun isOptional(an: List<AnnotationInfo>): Boolean? {
        return null
    }

    fun isNullable(an: List<AnnotationInfo>): Boolean? {
        return if (an.any { it.name.endsWith(".Nullable") }) {
            true
        } else {
            null
        }
    }

    fun resolveFiledInfoFromField(f: FieldInfo): ResolvedFieldInfo {
        TODO()
    }

    fun resolveFieldInfoFromGetterOrSetter(f: MethodInfo): ResolvedFieldInfo {
        val name = if(f.name.startsWith("is")){
            f.name[2].lowercase() + f.name.substring(3)
        }else{
            f.name[3].lowercase() + f.name.substring(4)
        }
        if (f.parameterInfo.isNotEmpty()) {
            TODO()
        } else {
            return ResolvedFieldInfo(
                name = name,
                type = f.typeSignatureOrTypeDescriptor.resultType,
                optional = null,
                nullable = isNullable(f.annotationInfo)
            )
        }
    }


    fun resolveFieldInfoFromConstructorParameter(f: MethodParameterInfo): ResolvedFieldInfo {
        val parentKClass = f.methodInfo.classInfo.kotlinClass()
        val optional = if (parentKClass != null) {
            val ctor = parentKClass.constructors.first()
            val paramIndex = f.methodInfo.parameterInfo.indexOfFirst { it == f }
            ctor.valueParameters[paramIndex].declaresDefaultValue
        } else {
            false
        }
        return ResolvedFieldInfo(
            name = f.name,
            type = f.typeSignatureOrTypeDescriptor,
            optional = optional,
            nullable = isNullable(f.annotationInfo)
        )
    }
}


data class ResolvedDiscriminatedSubTypes(
    val discriminatorProperty: String,
    val options: List<SubtypeOption>
)

data class SubtypeOption(
    val classInfo: ClassInfo,
    val discriminatorValue: String
)

