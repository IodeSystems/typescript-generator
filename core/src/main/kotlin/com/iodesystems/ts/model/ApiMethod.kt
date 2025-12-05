package com.iodesystems.ts.model

data class ApiMethod(
    val name: String,
    val httpMethod: TsHttpMethod,
    val path: String,
    val requestBodyType: TsType?,
    val responseBodyType: TsType,
    val queryParamsType: TsType? = null,
    val pathReplacements: List<PathParam> = emptyList(),
) {
    data class PathParam(
        val name: String,
        val placeholder: String,
        val type: Type
    ) {
        enum class Type {
            STRING,
            NUMBER,
        }
    }
}
