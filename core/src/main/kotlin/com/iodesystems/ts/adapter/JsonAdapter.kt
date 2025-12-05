package com.iodesystems.ts.adapter

import com.iodesystems.ts.extractor.KotlinMetadata.kotlinClass
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinConstructor
import io.github.classgraph.*
import kotlinx.metadata.*

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
    fun chooseJsonConstructor(ci: ClassInfo, scan: ScanResult): MethodInfo? {
        // Is this a kotlin class?
        val ctors = ci.constructorInfo
            .filter { it.isPublic && !it.isSynthetic }
        if(ctors.isEmpty()) return null
        if (ctors.size == 1) return ctors.first()
        // Try to load the kotlin class, as this might be more useful
        return ci.kotlinClass()?.let { kc ->
            val kctors = kc.constructors.filter { ctor ->
                !ctor.isSecondary
            }
            kctors.singleOrNull()?.let { kctor ->
                val matching = ctors.first {
                    it.kotlinConstructor() == kctor
                }
                matching
            }
        }
    }

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

    // Given a combined set of annotations collected across field/getter/setter/ctor param
    // for the same logical property, adapters may derive a rename (e.g., @JsonProperty/@JsonAlias).
    // Default: no rename.
    fun resolveRenameFromAnnotations(annotations: List<AnnotationInfo>): String? = null

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
        val rename: String?,
        val type: HierarchicalTypeSignature,
        val optional: Boolean?,
        val nullable: Boolean?,
        val annotations: List<AnnotationInfo>,
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
        return ResolvedFieldInfo(
            name = f.name,
            rename = null,
            type = f.typeSignatureOrTypeDescriptor,
            optional = isOptional(f.annotationInfo?.toList() ?: emptyList()),
            nullable = isNullable(f.annotationInfo),
            annotations = f.annotationInfo?.toList() ?: emptyList()
        )
    }

    fun resolveFieldInfoFromGetterOrSetter(f: MethodInfo): ResolvedFieldInfo {
        val methodName = f.name
        val (propName, isSetter) = when {
            methodName.startsWith("is") && methodName.length > 2 ->
                (methodName[2].lowercase() + methodName.substring(3)) to false
            methodName.startsWith("get") && methodName.length > 3 ->
                (methodName[3].lowercase() + methodName.substring(4)) to false
            methodName.startsWith("set") && methodName.length > 3 ->
                (methodName[3].lowercase() + methodName.substring(4)) to true
            else -> methodName to (f.parameterInfo.isNotEmpty())
        }

        return if (isSetter) {
            val p = f.parameterInfo.firstOrNull()
            ResolvedFieldInfo(
                name = propName,
                rename = null,
                type = p?.typeSignatureOrTypeDescriptor ?: f.typeSignatureOrTypeDescriptor.resultType,
                optional = isOptional(((p?.annotationInfo?.toList() ?: emptyList()) + (f.annotationInfo?.toList()
                    ?: emptyList()))),
                nullable = isNullable((p?.annotationInfo?.toList() ?: emptyList()) + (f.annotationInfo?.toList()
                    ?: emptyList())),
                annotations = (p?.annotationInfo?.toList() ?: emptyList()) + (f.annotationInfo?.toList() ?: emptyList())
            )
        } else {
            ResolvedFieldInfo(
                name = propName,
                rename = null,
                type = f.typeSignatureOrTypeDescriptor.resultType,
                optional = isOptional(f.annotationInfo?.toList() ?: emptyList()),
                nullable = isNullable(f.annotationInfo),
                annotations = f.annotationInfo?.toList() ?: emptyList()
            )
        }
    }


    fun resolveFieldInfoFromConstructorParameter(f: MethodParameterInfo): ResolvedFieldInfo {
        val optional = f.methodInfo.kotlinConstructor()?.let { ctor ->
            val paramIndex = f.methodInfo.parameterInfo.indexOfFirst { it == f }
            ctor.valueParameters[paramIndex].declaresDefaultValue
        }
        return ResolvedFieldInfo(
            name = f.name,
            rename = null,
            type = f.typeSignatureOrTypeDescriptor,
            optional = optional ?: isOptional(f.annotationInfo?.toList() ?: emptyList()),
            nullable = isNullable(f.annotationInfo),
            annotations = f.annotationInfo?.toList() ?: emptyList()
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

