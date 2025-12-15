package com.iodesystems.ts.model

data class ApiModel(
    val tsBaseName: String,
    val jvmQualifiedClassName: String,
    val basePath: String,
    val apiMethods: List<ApiMethod>,
)