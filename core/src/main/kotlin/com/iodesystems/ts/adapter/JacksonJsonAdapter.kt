package com.iodesystems.ts.adapter

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.github.classgraph.AnnotationEnumValue
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult

// Default no-op Jackson adapter (baseline behavior remains unchanged for now)
class JacksonJsonAdapter : JsonAdapter {
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
        baseType: ClassInfo,
    ): ResolvedDiscriminatedSubTypes? {
        // Mirror previous logic: only consider polymorphism for return-position annotated bases
        val jsonTypeInfo = baseType.annotationInfo.get(JsonTypeInfo::class.java.name) ?: return null

        val useParam = jsonTypeInfo.parameterValues
            .firstOrNull { it.name == "use" }?.value as? AnnotationEnumValue
        val id = JsonTypeInfo.Id.valueOf(useParam?.valueName!!)
        val discriminatorProperty = (jsonTypeInfo.parameterValues
            .firstOrNull { it.name == "property" }
            ?.value as? String).let {
            if (it.isNullOrBlank()) id.defaultPropertyName else it
        }

        val impls = scan.getClassesImplementing(baseType.name).sortedBy { it.simpleName }
        val options = impls.map { impl ->
            val discValue = when (id) {
                JsonTypeInfo.Id.CLASS -> impl.name
                JsonTypeInfo.Id.SIMPLE_NAME -> impl.simpleName
                JsonTypeInfo.Id.NAME -> {
                    val jtn = impl.annotationInfo.get(JsonTypeName::class.java.name)
                    val v = jtn?.parameterValues?.firstOrNull { it.name == "value" }?.value as? String
                    v ?: impl.simpleName
                }

                else -> error("Unsupported type $id")
            }
            SubtypeOption(impl, discValue)
        }
        return ResolvedDiscriminatedSubTypes(discriminatorProperty, options)
    }

    override fun chooseJsonConstructor(ci: ClassInfo, scan: ScanResult): MethodInfo? {
        val ctors = ci.constructorInfo
            .filter { it.isPublic && !it.isSynthetic }
        // Prefer a constructor annotated with @JsonCreator
        val creator = ctors.firstOrNull { ctor ->
            ctor.annotationInfo.get(com.fasterxml.jackson.annotation.JsonCreator::class.java.name) != null
        }
        if (creator != null) return creator
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