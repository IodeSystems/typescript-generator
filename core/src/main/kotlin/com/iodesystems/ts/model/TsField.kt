package com.iodesystems.ts.model

data class TsField(
    val name: String,
    val type: TsType,
    val optional: Boolean,
    val nullable: Boolean,
)