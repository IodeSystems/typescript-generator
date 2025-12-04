package com.iodesystems.ts.model


// Result of extracting APIs and Types from a ScanResult
data class ExtractionResult(
    val apis: List<ApiModel>,
    val types: List<TsType>,
)

data class ExtractionResultNew(
    val apis: List<ApiModelNew>,
    val types: List<TsTypeNew>,
    val typeReferences: List<TsRefNew>,
)