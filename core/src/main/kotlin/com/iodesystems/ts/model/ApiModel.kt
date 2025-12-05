package com.iodesystems.ts.model

data class ApiModel(
    val tsBaseName: String,
    val jvmQualifiedClassName: String,
    val basePath: String,
    val apiMethods: List<ApiMethod>,
) {
    fun typesUsed(): List<TsType> {
        val typeUsed = mutableSetOf<TsType>()
        fun addType(type: TsType) {
            when (type) {
                is TsType.Inline -> {}
                is TsType.Object -> typeUsed.add(type)
                is TsType.Union -> typeUsed.add(type)
                is TsType.Enum -> typeUsed.add(type)
            }
        }
        apiMethods.forEach { method ->
            method.requestBodyType?.let { addType(it) }
            addType(method.responseBodyType)
        }
        return typeUsed.toList()
    }
}