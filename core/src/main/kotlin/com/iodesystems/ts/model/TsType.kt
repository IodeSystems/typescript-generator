package com.iodesystems.ts.model

// TypeScript type descriptor produced by extraction
data class TsType(
    val jvmQualifiedClassName: String,
    val typeScriptTypeName: String,
    val body: TsBody,
    val references: List<TsRef> = emptyList(),
    // TypeScript intersections to append to this alias declaration (interface names, etc.)
    val intersects: List<TsType> = emptyList(),
    // Generic type parameter names for this alias declaration (e.g., ["T","U"]) when this TsType is a generic alias
    val genericParameters: List<String> = emptyList(),
)

data class TsFieldNew(
    val tsName: String,
    val optional: Boolean,
    val nullable: Boolean,
)

data class TsRefNew(
    val fromTsBaseName: String,
    val toTsBaseName: String,
    val refType: Type
) {
    enum class Type {
        METHOD,
        TYPE,
    }
}

sealed interface TsTypeNew {
    val jvmQualifiedClassName: String
    val tsName: String
    val isOptional: Boolean
    val isNullable: Boolean
    val tsGenericParameters: Map<String, Inline>


    fun inlineReference(
        definedGenerics: Map<String, Inline> = emptyMap(),
    ): Inline {
        return Inline(
            jvmQualifiedClassName = jvmQualifiedClassName,
            tsName = tsName,
            tsGenericParameters = tsGenericParameters.map { (name, existing) ->
                name to (definedGenerics[name] ?: existing)
            }.toMap(),
            isOptional = isOptional,
            isNullable = isNullable
        )
    }

    data class Inline(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean,
        override val isNullable: Boolean,
        override val tsGenericParameters: Map<String, Inline>,
    ) : TsTypeNew

    data class Object(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean,
        override val isNullable: Boolean,
        val fields: Map<String, TsFieldNew>,
        val discriminator: Pair<String, String>? = null,
        override val tsGenericParameters: Map<String, Inline>,
    ) : TsTypeNew

    data class Union(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean,
        override val isNullable: Boolean,
        val discriminatorField: String,
        val children: List<TsTypeNew> = emptyList(),
    ) : TsTypeNew {
        override val tsGenericParameters: Map<String, Inline> = emptyMap()
    }

}