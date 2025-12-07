package com.iodesystems.ts.extractor

import com.iodesystems.ts.model.TsField

/**
 * Internal mutable graph representation used during extraction.
 * These nodes are not exposed to consumers; they are materialized into immutable TsType at emit time.
 */
sealed interface TsNode {
    val fqcn: String
    var tsName: String
    var isOptional: Boolean
    var isNullable: Boolean
}

data class NodeObject(
    override val fqcn: String,
    override var tsName: String,
    override var isOptional: Boolean,
    override var isNullable: Boolean,
    var fields: MutableMap<String, TsField> = mutableMapOf(),
    var discriminator: Pair<String, String>? = null,
    var supertypes: MutableList<String> = mutableListOf(),
    var generics: MutableMap<String, com.iodesystems.ts.model.TsType.Inline> = mutableMapOf(),
) : TsNode

data class NodeEnum(
    override val fqcn: String,
    override var tsName: String,
    override var isOptional: Boolean,
    override var isNullable: Boolean,
    var unionLiteral: String,
) : TsNode

data class NodeUnion(
    override val fqcn: String,
    override var tsName: String,
    override var isOptional: Boolean,
    override var isNullable: Boolean,
    var discriminatorField: String,
    var children: MutableList<String> = mutableListOf(),
    var supertypes: MutableList<String> = mutableListOf(),
    var generics: MutableMap<String, com.iodesystems.ts.model.TsType.Inline> = mutableMapOf(),
) : TsNode
