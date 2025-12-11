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
    val jvmQualifiedClassName: String
    val tsName: String
    val isOptional: Boolean
    val isNullable: Boolean
    val tsGenericParameters: Map<String, Inline>

    fun nonGlobalRelatedTypes(): List<TsType> {
        return if (listOf("string", "void", "number", "boolean").contains(tsName)) emptyList()
        else if (tsName.startsWith("Record<") || tsName.startsWith("Array<")) this.tsGenericParameters.values.flatMap { it.nonGlobalRelatedTypes() }
        else listOf(this) + this.tsGenericParameters.values.flatMap { it.nonGlobalRelatedTypes() }
    }

    fun withGenerics(definedGenerics: Map<String, Inline> = emptyMap()): TsType {
        val tsGenericParameters = tsGenericParameters.map { (name, existing) ->
            name to (definedGenerics[name] ?: existing)
        }.toMap()
        return when (this) {
            is Enum -> this
            is Inline -> copy(tsGenericParameters = tsGenericParameters)
            is Object -> copy(tsGenericParameters = tsGenericParameters)
            is Union -> copy(tsGenericParameters = tsGenericParameters)
        }
    }

    fun inlineReference(
        nullable: Boolean = isNullable,
        optional: Boolean = isOptional,
    ): Inline {
        return Inline(
            jvmQualifiedClassName = jvmQualifiedClassName,
            tsName = tsName,
            tsGenericParameters = tsGenericParameters,
            isOptional = optional,
            isNullable = nullable
        )
    }

    data class Inline(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean = false,
        override val isNullable: Boolean = false,
        override val tsGenericParameters: Map<String, Inline> = emptyMap(),
    ) : TsType

    data class Object(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean = false,
        override val isNullable: Boolean = false,
        val fields: Map<String, TsField> = emptyMap(),
        val discriminator: Pair<String, String>? = null,
        val supertypes: List<TsType> = emptyList(),
        override val tsGenericParameters: Map<String, Inline> = emptyMap(),
    ) : TsType

    data class Union(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean = false,
        override val isNullable: Boolean = false,
        val discriminatorField: String,
        val children: List<TsType> = emptyList(),
        val supertypes: List<TsType> = emptyList(),
        override val tsGenericParameters: Map<String, Inline> = emptyMap()
    ) : TsType

    data class Enum(
        override val jvmQualifiedClassName: String,
        override val tsName: String,
        override val isOptional: Boolean = false,
        override val isNullable: Boolean = false,
        val unionLiteral: String,
    ) : TsType {
        override val tsGenericParameters: Map<String, Inline> = emptyMap()
    }

}