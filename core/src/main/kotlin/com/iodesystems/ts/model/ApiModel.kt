package com.iodesystems.ts.model

// API description used by Emitter
data class ApiModel(
    val cls: String,
    val jvmQualifiedClassName: String,
    val basePath: String,
    val apiMethods: List<ApiMethod>,
)