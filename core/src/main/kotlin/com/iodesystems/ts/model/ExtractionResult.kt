package com.iodesystems.ts.model

// Result of extracting APIs and Types from a ScanResult
data class ExtractionResult(
    val apis: List<ApiModel>,
    val types: List<TsType>,
)