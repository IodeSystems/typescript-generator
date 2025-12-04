package com.iodesystems.ts.model

sealed interface TsBody{
    data class PrimitiveBody(val tsName: String) : TsBody
    data class ObjectBody(val tsFields: List<TsField>) : TsBody
    data class ArrayBody(val element: TsType) : TsBody
    // Discriminated union
    data class UnionBody(
        // val discriminatorField
        // val children: Map<String,TsType>
        val options: List<TsType>
    ) : TsBody
    /*
    data class UnionBody(
        val andGroup: List<TsBody>
    )
    */
}