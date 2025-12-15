package com.iodesystems.ts.model


data class TsField(
    val type: TsType,
    val optional: Boolean,
    val nullable: Boolean,
)

data class TsRef(
    val fromTsBaseName: String,
    val toTsBaseName: String,
    val refType: Type
) {
    enum class Type {
        METHOD,
        TYPE,
    }
}

sealed interface TsType {
    val fqcn: String
    val name: String
    val optional: Boolean
    val nullable: Boolean
    val generics: Map<String, Inline>

    fun nonGlobalRelatedTypes(): List<TsType> {
        return if (listOf("string", "void", "number", "boolean").contains(name)) emptyList()
        else if (name.startsWith("Record<") || name.startsWith("Array<")) this.generics.values.flatMap { it.nonGlobalRelatedTypes() }
        else listOf(this) + this.generics.values.flatMap { it.nonGlobalRelatedTypes() }
    }

    fun inlineReference(
        nullable: Boolean = this@TsType.nullable,
        optional: Boolean = this@TsType.optional,
    ): Inline {
        return Inline(
            fqcn = fqcn,
            name = name,
            generics = generics,
            optional = optional,
            nullable = nullable
        )
    }

    data class Inline(
        override val fqcn: String,
        override val name: String,
        override val optional: Boolean = false,
        override val nullable: Boolean = false,
        override val generics: Map<String, Inline> = emptyMap(),
    ) : TsType

    data class Object(
        override val fqcn: String,
        override val name: String,
        override val optional: Boolean = false,
        override val nullable: Boolean = false,
        val fields: Map<String, TsField> = emptyMap(),
        val discriminator: Pair<String, String>? = null,
        val intersections: List<TsType> = emptyList(),
        override val generics: Map<String, Inline> = emptyMap(),
    ) : TsType

    data class Union(
        override val fqcn: String,
        override val name: String,
        override val optional: Boolean = false,
        override val nullable: Boolean = false,
        val discriminatorField: String,
        val children: List<TsType> = emptyList(),
        val supertypes: List<TsType> = emptyList(),
        override val generics: Map<String, Inline> = emptyMap()
    ) : TsType

    data class Enum(
        override val fqcn: String,
        override val name: String,
        override val optional: Boolean = false,
        override val nullable: Boolean = false,
        val unionLiteral: String,
    ) : TsType {
        override val generics: Map<String, Inline> = emptyMap()
    }

    /**
     * Represents a type alias that maps a JVM type to a TypeScript type.
     * Used for user-configured mapped types like `ByteString -> string`.
     */
    data class Alias(
        override val fqcn: String,
        override val name: String,
        override val optional: Boolean = false,
        override val nullable: Boolean = false,
        val aliasTo: String,
    ) : TsType {
        override val generics: Map<String, Inline> = emptyMap()
    }

}