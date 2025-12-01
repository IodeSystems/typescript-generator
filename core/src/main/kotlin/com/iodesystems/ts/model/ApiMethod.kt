package com.iodesystems.ts.model

data class ApiMethod(
    val name: String,
    val httpMethod: TsHttpMethod,
    val path: String,
    val requestBodyType: TsType?,
    val responseBodyType: TsType?,
    // Optional query parameters bag type (combined from @RequestParam and non-body params)
    val queryParamsType: TsType? = null,
    // Map of path template key -> TsField (property name + type)
    val pathTsFields: Map<String, TsField> = emptyMap(),
)