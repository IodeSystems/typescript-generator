@file:Suppress("KotlinUnreachableCode")

package com.iodesystems.ts.extractor

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertEq
import com.iodesystems.ts.lib.Asserts.assertIsEmpty
import com.iodesystems.ts.lib.Asserts.assertNonNull
import com.iodesystems.ts.lib.Asserts.assertSingle
import com.iodesystems.ts.lib.Asserts.assertSingleKey
import com.iodesystems.ts.lib.Asserts.assertType
import com.iodesystems.ts.lib.Asserts.toJson
import com.iodesystems.ts.Config
import com.iodesystems.ts.Scanner
import com.iodesystems.ts.lib.Tree
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import org.springframework.web.bind.annotation.*
import kotlin.test.Test


class ExtractorTest {

    companion object {
        inline fun <reified T> extract(
            crossinline fn: (Config.Builder.() -> Config.Builder) = { this }
        ): JvmExtractor.Extraction {
            val config = Config().build { includeApi(T::class).fn() }
            val scan = Scanner(config).scan()
            val apiRegistry = config.apiExtractor().extract(scan)
            return JvmExtractor(config).buildFromRegistry(scan, apiRegistry)
        }
    }

    @RestController
    @RequestMapping
    class Simple {
        @PostMapping
        fun post(
            @RequestBody
            req: Boolean
        ) {
        }
    }

    @Test
    fun simple() {
        val extraction = extract<Simple>()
        extraction.apis.assertSingle("There should be one api extracted")
        extraction.types.assertIsEmpty("There should be no generated types")
        extraction.typeReferences.assertIsEmpty("There should be no generated types")
    }

    @RestController
    @RequestMapping
    class Optional {
        @PostMapping
        fun post(
            @RequestBody
            req: Boolean? = null
        ): String? = null
    }

    @Test
    fun optional() {
        val extraction = extract<Optional>()
        extraction.types.assertIsEmpty("There should be no generated types")
        extraction.typeReferences.assertIsEmpty("There should be no generated types")
        val api = extraction.apis.assertSingle("There should be one api extracted")
        val method = api.apiMethods.assertSingle("There should be a single method")
        method.requestBodyType.assertNonNull(
            """
            There should be a request body
        """.trimIndent()
        ).let { body ->
            body.isOptional.assertEq(
                true, """
                The request body should be optional
            """.trimIndent()
            )
            body.isNullable.assertEq(
                true, """
                The request body should be nullable
            """.trimIndent()
            )
        }

        method.responseBodyType.assertNonNull(
            """
            There should be a response
        """.trimIndent()
        ).let { rsp ->
            rsp.isOptional.assertEq(
                false, """
                The response should not be optional
            """.trimIndent()
            )
            rsp.isNullable.assertEq(
                true, """
                The response should be nullable
            """.trimIndent()
            )
        }
    }


    @RestController
    @RequestMapping
    class SimpleType {
        data class Request(
            val optionalAndNullable: String? = null,
            val optional: String = "",
            val required: String
        )

        @PostMapping
        fun post(
            @RequestBody
            req: Request
        ) {
        }
    }

    @Test
    fun types() {
        val extraction = extract<SimpleType>()
        extraction.apis.assertSingle("There should be one api extracted")
        extraction.typeReferences.assertSingle("There should be a single reference").let { ref ->
            ref.fromTsBaseName.assertEq("ExtractorTestSimpleType#post", "Reference from is invalid")
            ref.toTsBaseName.assertEq("ExtractorTestSimpleTypeRequest", "Reference from is invalid")
            ref.refType.assertEq(TsRef.Type.METHOD, "Reference from is invalid")
        }
        extraction.types
            .assertSingle("There should be a single request type")
            .assertType<TsType.Object>("Request type is the wrong type")
            .let { type ->
                type.fields.size.assertEq(3, "There should be 3 fields on the request type")
                type.fields["optionalAndNullable"].assertNonNull(
                    "There should be a request field optionalAndNullable"
                ).let { field ->
                    field.optional.assertEq(true, "The optionalAndNullable should be optional")
                    field.nullable.assertEq(true, "The optionalAndNullable should be nullable")
                }
            }
    }

    @RestController
    @RequestMapping
    class SimpleTypeExtension {
        interface IRequest {
            val required: String
        }

        open class BaseRequest(
            override val required: String
        ) : IRequest

        class ExtendedRequest(
            val additionalField: String
        ) : BaseRequest(
            required = "required"
        )

        @PostMapping
        fun postExtended(
            @RequestBody
            req: ExtendedRequest
        ) {
        }
    }

    @Test
    fun typesExtended() {
        val extraction = extract<SimpleTypeExtension>()
        extraction.apis.assertSingle("There should be one api extracted")
        extraction.types.size.assertEq(3, "The request, base, and iface should be registered")
        extraction.typeReferences.size.assertEq(4, "The items should be referenced properly")
    }


    @RestController
    @RequestMapping
    class SimpleArrays {
        data class Request(
            val items: List<String>,
            val optionalItems: List<String> = emptyList(),
            val nullableItems: List<String>?,
            val optionalAndNullableItems: List<String>? = null
        )

        @PostMapping
        fun postExtended(
            @RequestBody
            req: List<Request>
        ) {
        }
    }

    @Test
    fun simpleArrays() {
        val extraction = extract<SimpleArrays>()
        extraction.apis.assertSingle("There should be one api extracted")
            .apiMethods.assertSingle("There should be a single method")
            .let { method ->
                method.requestBodyType
                    .assertNonNull("There should be a request body")
                    .assertType<TsType.Inline>("Request type is invalid")
                    .let { req ->
                        req.isNullable.assertEq(false, "There should be a response body")
                        req.isOptional.assertEq(false, "There should be a optional body")
                        req.tsGenericParameters.assertSingleKey("T", "The reference should be inlined")
                            .let { ref ->
                                ref.tsName.assertEq("ExtractorTestSimpleArraysRequest", "tsBaseName is incorrect")
                                ref.isNullable.assertEq(false, "The array reference should not be nullable")
                                ref.isOptional.assertEq(false, "The array reference should not be optional")
                            }
                    }
            }
        extraction.types.assertSingle("There should be a single type")
            .assertType<TsType.Object>("Should be an object")
            .let { el ->
                el.fields.size.assertEq(4, "There should be 4 fields on the request")
                el.fields["items"].assertNonNull("items is missing")
                    .let {
                        it.optional.assertEq(false, "items is not optional")
                        it.nullable.assertEq(false, "items is not nullable")
                    }
                el.fields["optionalItems"].assertNonNull("optionalItems is missing")
                    .let {
                        it.optional.assertEq(true, "optionalItems is optional")
                        it.nullable.assertEq(false, "optionalItems is not nullable")
                    }
                el.fields["nullableItems"].assertNonNull("nullableItems is missing")
                    .let {
                        it.optional.assertEq(false, "nullableItems is not optional")
                        it.nullable.assertEq(true, "nullableItems is nullable")
                    }
                el.fields["optionalAndNullableItems"].assertNonNull("optionalAndNullableItems is missing")
                    .let {
                        it.optional.assertEq(true, "optionalAndNullableItems is optional")
                        it.nullable.assertEq(true, "optionalAndNullableItems is nullable")
                    }
            }
    }

    @RestController
    @RequestMapping
    class Generics {
        data class Request<T>(
            val item: T,
            val items: List<T>
        )

        data class Response<T>(
            val items: List<T>,
        )

        @PostMapping
        fun post(
            @RequestBody
            req: Request<String>
        ): Response<Boolean> = error("dummy")
    }

    @Test
    fun generics() {
        val extraction = extract<Generics>()
        extraction.apis.assertSingle("There should be one api extracted")
            .apiMethods.assertSingle("There should be a single method")
            .let { method ->
                method.requestBodyType
                    .assertNonNull("There should be a request body")
                    .assertType<TsType.Inline>("Request type is invalid")
                    .let { req ->
                        req.isNullable.assertEq(false, "There should be a response body")
                        req.isOptional.assertEq(false, "There should be a optional body")
                        req.tsGenericParameters.assertSingleKey("T", "There should be a generic parameter")
                            .let { gen ->
                                gen.tsName.assertEq(
                                    "string",
                                    "the generic parameter should be constrained for request bodies that constrain it"
                                )
                            }

                    }
            }
        extraction.types.size.assertEq(2, "There should be 2 types")
        extraction.types[0]
            .assertType<TsType.Object>("Should be an object")
            .let { el ->
                el.jvmQualifiedClassName.assertEq(
                    $$"com.iodesystems.ts.extractor.ExtractorTest$Generics$Request",
                    "Wrong jvm name for request"
                )
                el.fields.size.assertEq(2, "There should be 2 fields on the request")
                el.fields["item"].assertNonNull("item is missing")
                    .let {
                        it.optional.assertEq(false, "item is not optional")
                        it.nullable.assertEq(false, "item is not nullable")
                    }
                el.fields["items"].assertNonNull("items is missing")
                    .let {
                        it.optional.assertEq(false, "items is not optional")
                        it.nullable.assertEq(false, "items is not nullable")
                    }
            }
        extraction.types[1]
            .assertType<TsType.Object>("Should be an object")
            .let { el ->
                el.jvmQualifiedClassName.assertEq(
                    $$"com.iodesystems.ts.extractor.ExtractorTest$Generics$Response",
                    "Wrong jvm name for response"
                )
                el.fields.size.assertEq(1, "There should be 1 fields on the request")
                el.fields["items"].assertNonNull("items is missing")
                    .let {
                        it.optional.assertEq(false, "items is not optional")
                        it.nullable.assertEq(false, "items is not nullable")
                    }
            }

        extraction.typeReferences.size.assertEq(2, "There should be type references, request and response")
    }

    @RestController
    @RequestMapping
    class Maps {
        @GetMapping
        fun get() = mutableMapOf<String, Number?>()
    }

    @Test
    fun testMaps() {
        val ext = extract<Maps>()
        ext.apis.assertSingle("There should be one api extracted")
            .apiMethods.assertSingle("There should be a single method")
            .let { method ->
                method.responseBodyType
                    .assertNonNull("There should be a response body")
                    .assertType<TsType.Inline>("Response type is invalid")
                    .let { resp ->
                        resp.tsName.assertEq("Record<K,V>", "Response type should be Record")
                        resp.tsGenericParameters["K"].assertNonNull("K parameter missing")
                            .let { k ->
                                k.tsName.assertEq("string", "K should be string type")
                                k.isNullable.assertEq(false, "K should not be nullable")
                                k.isOptional.assertEq(false, "K should not be optional")
                            }
                        resp.tsGenericParameters["V"].assertNonNull("V parameter missing")
                            .let { v ->
                                v.tsName.assertEq("number", "V should be number type")
                                v.isNullable.assertEq(true, "V should be nullable")
                                v.isOptional.assertEq(false, "V should not be optional")
                            }
                    }
            }
        ext.types.assertIsEmpty("There should be no generated types")
        ext.typeReferences.assertIsEmpty("There should be no type references")
    }

    @RestController
    @RequestMapping
    class GenericArrays {
        data class Request<T>(
            val item: T,
            val items: List<T>
        )

        @GetMapping
        fun get() = listOf<Request<Boolean?>>()

        @PostMapping
        fun post(
            @RequestBody
            req: List<Request<String>>
        ) {
        }
    }

    @Test
    fun genericArrays() {
        val extraction = extract<GenericArrays>()
        println(extraction.toJson())
        val api = extraction.apis.assertSingle("There should be one api extracted")
        api.apiMethods.size.assertEq(2, "There should be two api methods")
        api.apiMethods[0].let { method ->
            val allParamsAndGenerics = Tree.treeToListDepthFirst(
                Pair(
                    "INITIAL", method.responseBodyType
                        .assertNonNull("There should be a single generic request")
                )
            ) {
                it.second.tsGenericParameters.toList()
            }
            allParamsAndGenerics.size.assertEq(3, "There should be 2 generic parameters")
            allParamsAndGenerics[1].let {
                it.first.assertEq("T", "The first parameter should be T")
                it.second.tsName.assertEq(
                    "ExtractorTestGenericArraysRequest<T>",
                    "The second parameter should be a generic parameter"
                )
            }
            allParamsAndGenerics[2].let {
                it.first.assertEq("T", "The second parameter should be T")
                it.second.tsName.assertEq(
                    "boolean",
                    "The second parameter should be a constrained generic parameter"
                )
                it.second.isNullable.assertEq(true, "the type parameter should be nullable")
            }
        }
        api.apiMethods[1].let { method ->
            val allParamsAndGenerics = Tree.treeToListDepthFirst(
                Pair(
                    "INITIAL", method.requestBodyType
                        .assertNonNull("There should be a single generic request")
                )
            ) {
                it.second.tsGenericParameters.toList()
            }
            allParamsAndGenerics.size.assertEq(3, "There should be 2 generic parameters")
            allParamsAndGenerics[1].let {
                it.first.assertEq("T", "The first parameter should be T")
                it.second.tsName.assertEq(
                    "ExtractorTestGenericArraysRequest<T>",
                    "The second parameter should be a generic parameter"
                )
            }
            allParamsAndGenerics[2].let {
                it.first.assertEq("T", "The second parameter should be T")
                it.second.tsName.assertEq(
                    "string",
                    "The second parameter should be a constrained generic parameter"
                )
            }

        }
        extraction.types.assertSingle("There should be a single type")
            .assertType<TsType.Object>("Should be an object")
            .let { el ->
                el.fields.size.assertEq(2, "There should be 4 fields on the request")
                el.fields["item"].assertNonNull("item is missing")
                    .let {
                        it.optional.assertEq(false, "item is not optional")
                        it.nullable.assertEq(false, "item is not nullable")
                    }
                el.fields["items"].assertNonNull("items is missing")
                    .let {
                        it.optional.assertEq(false, "items is not optional")
                        it.nullable.assertEq(false, "items is not nullable")
                    }
            }
    }

    @RequestMapping
    @RestController
    class Unions {

        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
        sealed interface Union {
            data object Ok : Union
            data object Uhoh : Union
        }

        @PostMapping
        fun post(
            @RequestBody
            req: Union
        ): Union = error("test")
    }

    @Test
    fun typeUnions() {
        val extraction = extract<Unions>()
        println(extraction.toJson())
    }
}