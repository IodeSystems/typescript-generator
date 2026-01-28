package com.iodesystems.ts.extractor.extractors

import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.extractor.KotlinMetadata.kotlinMethod
import com.iodesystems.ts.extractor.registry.ApiMethodDescriptor
import com.iodesystems.ts.lib.AnnotationUtils
import com.iodesystems.ts.model.ApiMethod
import com.iodesystems.ts.model.TsField
import com.iodesystems.ts.model.TsType
import io.github.classgraph.MethodInfo
import kotlinx.metadata.KmFunction
import kotlinx.metadata.declaresDefaultValue
import kotlinx.metadata.isNullable
import org.springframework.web.bind.annotation.RequestBody
import java.lang.reflect.Method

data class JvmMethod(
    val jvmExtractor: JvmExtractor,
    val apiMethod: ApiMethodDescriptor,
    val fqcn: String,
    val methodInfo: MethodInfo,
    val apiTsName: String,
    val methodKmFun: KmFunction?
) {
    private val classRef by lazy {
        ClassReference(jvmExtractor.config, jvmExtractor.scan, jvmExtractor.typeCache, jvmExtractor.jsonAdapter)
    }

    private val reflectedMethod: Method? by lazy {
        try {
            val clazz = methodInfo.classInfo.loadClass()
            clazz.methods.firstOrNull { m ->
                m.name == methodInfo.name &&
                        m.parameterCount == methodInfo.parameterInfo.size
            }
        } catch (e: Exception) {
            null
        }
    }

    val requestType: TsType? by lazy {
        extractRequestBodyType()
    }

    val responseType: TsType by lazy {
        extractResponseType()
    }

    val queryParametersType: TsType? by lazy {
        extractQueryParamsType()
    }

    val pathParameters: List<ApiMethod.PathParam> by lazy {
        extractPathParameters()
    }

    private fun extractRequestBodyType(): TsType? {
        val method = reflectedMethod ?: return null
        val kmFun = methodInfo.kotlinMethod()

        // Find @RequestBody parameter using name-based lookup to handle classloader isolation
        method.parameters.forEachIndexed { idx, param ->
            if (AnnotationUtils.hasAnnotation(param, RequestBody::class)) {
                val genericType = method.genericParameterTypes[idx]
                val kmParam = kmFun?.valueParameters?.getOrNull(idx)
                val isNullable = kmParam?.type?.isNullable ?: false
                val isOptional = kmParam?.declaresDefaultValue ?: false

                return classRef.toTsType(genericType, isNullable, isOptional, emptyMap(), kmParam?.type)
            }
        }

        return null
    }

    private fun extractResponseType(): TsType {
        val method = reflectedMethod ?: return TsType.Inline(
            fqcn = "void",
            name = "void",
            nullable = false,
            optional = false
        )

        val kmFun = methodInfo.kotlinMethod()
        val genericReturnType = method.genericReturnType

        // Check for void/Unit
        if (genericReturnType == Void.TYPE ||
            genericReturnType == Void::class.java ||
            genericReturnType.typeName == "kotlin.Unit"
        ) {
            return TsType.Inline(
                fqcn = "void",
                name = "void",
                nullable = false,
                optional = false
            )
        }

        val isNullable = kmFun?.returnType?.isNullable ?: false
        return classRef.toTsType(genericReturnType, isNullable, false, emptyMap(), kmFun?.returnType)
    }

    private fun extractQueryParamsType(): TsType? {
        val method = reflectedMethod ?: return null
        val kmFun = methodInfo.kotlinMethod()

        // Use the ApiAdapter to discover query parameters
        val adapterQueryParams = jvmExtractor.apiAdapter.resolveQueryParams(methodInfo)
        if (adapterQueryParams.isEmpty()) return null

        val queryFields = mutableMapOf<String, TsField>()

        adapterQueryParams.forEach { qp ->
            val genericType = method.genericParameterTypes.getOrNull(qp.index) ?: return@forEach
            val kmParam = kmFun?.valueParameters?.getOrNull(qp.index)
            val isNullable = kmParam?.type?.isNullable ?: false
            val isOptional = qp.optional || (kmParam?.declaresDefaultValue ?: false)

            val fieldType = classRef.toTsType(genericType, isNullable, false)
            queryFields[qp.name] = TsField(
                type = fieldType,
                optional = isOptional,
                nullable = isNullable
            )
        }

        if (queryFields.isEmpty()) return null

        // Create a synthetic query params type
        val queryTypeName = "${apiTsName}${methodInfo.name.replaceFirstChar { it.uppercase() }}Query"
        return TsType.Object(
            fqcn = "$fqcn:QueryParams",
            name = queryTypeName,
            fields = queryFields
        )
    }

    private fun extractPathParameters(): List<ApiMethod.PathParam> {
        val method = reflectedMethod ?: return emptyList()

        // Use the ApiAdapter to discover path parameters
        val adapterPathParams = jvmExtractor.apiAdapter.resolvePathParams(methodInfo)

        return adapterPathParams.map { pp ->
            val genericType = method.genericParameterTypes.getOrNull(pp.index)

            // Determine the type (STRING or NUMBER)
            val paramType = when (genericType) {
                Long::class.javaObjectType, Long::class.javaPrimitiveType,
                Int::class.javaObjectType, Int::class.javaPrimitiveType,
                Short::class.javaObjectType, Short::class.javaPrimitiveType,
                Byte::class.javaObjectType, Byte::class.javaPrimitiveType,
                Double::class.javaObjectType, Double::class.javaPrimitiveType,
                Float::class.javaObjectType, Float::class.javaPrimitiveType -> ApiMethod.PathParam.Type.NUMBER

                else -> ApiMethod.PathParam.Type.STRING
            }

            ApiMethod.PathParam(
                name = pp.name,
                placeholder = pp.placeholder,
                type = paramType
            )
        }
    }
}