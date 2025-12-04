package com.iodesystems.ts.model

// API description used by Emitter
data class ApiModel(
    val tsBaseName: String,
    val jvmQualifiedClassName: String,
    val basePath: String,
    val apiMethods: List<ApiMethod>,
)

data class ApiModelNew(
    val tsBaseName: String,
    val jvmQualifiedClassName: String,
    val basePath: String,
    val apiMethods: List<ApiMethodNew>,
)