package com.iodesystems.ts.model

// TypeScript type descriptor produced by extraction
data class TsType(
    val jvmQualifiedClassName: String,
    val typeScriptTypeName: String,
    val body: TsBody,
    val references: List<TsRef> = emptyList(),
    // TypeScript intersections to append to this alias declaration (interface names, etc.)
    val intersects: List<String> = emptyList(),
    // Generic type parameter names for this alias declaration (e.g., ["T","U"]) when this TsType is a generic alias
    val genericParameters: List<String> = emptyList(),
)