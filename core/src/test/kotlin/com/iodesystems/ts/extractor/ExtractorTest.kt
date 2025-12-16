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
import com.iodesystems.ts.lib.TestUtils.extract
import com.iodesystems.ts.lib.Tree
import com.iodesystems.ts.model.TsRef
import com.iodesystems.ts.model.TsType
import org.springframework.web.bind.annotation.*
import kotlin.test.Test


class ExtractorTest {

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
            body.optional.assertEq(
                true, """
                The request body should be optional
            """.trimIndent()
            )
            body.nullable.assertEq(
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
            rsp.optional.assertEq(
                false, """
                The response should not be optional
            """.trimIndent()
            )
            rsp.nullable.assertEq(
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
        extraction.typeReferences.size.assertEq(3, "The items should be referenced properly")
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
                        req.nullable.assertEq(false, "There should be a response body")
                        req.optional.assertEq(false, "There should be a optional body")
                        req.generics.assertSingleKey("T", "The reference should be inlined")
                            .let { ref ->
                                ref.name.assertEq("ExtractorTestSimpleArraysRequest", "tsBaseName is incorrect")
                                ref.nullable.assertEq(false, "The array reference should not be nullable")
                                ref.optional.assertEq(false, "The array reference should not be optional")
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
                        req.nullable.assertEq(false, "There should be a response body")
                        req.optional.assertEq(false, "There should be a optional body")
                        req.generics.assertSingleKey("T", "There should be a generic parameter")
                            .let { gen ->
                                gen.name.assertEq(
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
                el.fqcn.assertEq(
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
                el.fqcn.assertEq(
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
                        resp.name.assertEq("Record<K,V>", "Response type should be Record")
                        resp.generics["K"].assertNonNull("K parameter missing")
                            .let { k ->
                                k.name.assertEq("string", "K should be string type")
                                k.nullable.assertEq(false, "K should not be nullable")
                                k.optional.assertEq(false, "K should not be optional")
                            }
                        resp.generics["V"].assertNonNull("V parameter missing")
                            .let { v ->
                                v.name.assertEq("number", "V should be number type")
                                v.nullable.assertEq(true, "V should be nullable")
                                v.optional.assertEq(false, "V should not be optional")
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
                it.second.generics.toList()
            }
            allParamsAndGenerics.size.assertEq(3, "There should be 2 generic parameters")
            allParamsAndGenerics[1].let {
                it.first.assertEq("T", "The first parameter should be T")
                it.second.name.assertEq(
                    "ExtractorTestGenericArraysRequest<T>",
                    "The second parameter should be a generic parameter"
                )
            }
            allParamsAndGenerics[2].let {
                it.first.assertEq("T", "The second parameter should be T")
                it.second.name.assertEq(
                    "boolean",
                    "The second parameter should be a constrained generic parameter"
                )
                it.second.nullable.assertEq(true, "the type parameter should be nullable")
            }
        }
        api.apiMethods[1].let { method ->
            val allParamsAndGenerics = Tree.treeToListDepthFirst(
                Pair(
                    "INITIAL", method.requestBodyType
                        .assertNonNull("There should be a single generic request")
                )
            ) {
                it.second.generics.toList()
            }
            allParamsAndGenerics.size.assertEq(3, "There should be 2 generic parameters")
            allParamsAndGenerics[1].let {
                it.first.assertEq("T", "The first parameter should be T")
                it.second.name.assertEq(
                    "ExtractorTestGenericArraysRequest<T>",
                    "The second parameter should be a generic parameter"
                )
            }
            allParamsAndGenerics[2].let {
                it.first.assertEq("T", "The second parameter should be T")
                it.second.name.assertEq(
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

        extraction.typeReferences.size.assertEq(
            2,
            "There should be type references, request and response, not of the Array type, but of the generic parameter"
        )
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

        // One API with one method
        val api = extraction.apis.assertSingle("There should be one api extracted")
        val method = api.apiMethods.assertSingle("There should be a single method")

        // Find the union type and verify its structure
        val union = extraction.types
            .firstOrNull { it.name == "ExtractorTestUnionsUnionUnion" }
            .assertNonNull("The union alias type should be generated with expected name")
            .assertType<TsType.Union>("The union alias should be a TsType.Union")

        // Request/Response should reference the union alias (non-optional/non-nullable)
        method.requestBodyType.assertNonNull("There should be a request body type")
            .let { rb ->
                rb.name.assertEq(union.name, "Request should be the union alias")
                rb.optional.assertEq(false, "Request union should not be optional")
                rb.nullable.assertEq(false, "Request union should not be nullable")
            }
        method.responseBodyType.assertNonNull("There should be a response body type")
            .let { rsp ->
                rsp.name.assertEq(union.name, "Response should be the union alias")
                rsp.optional.assertEq(false, "Response union should not be optional")
                rsp.nullable.assertEq(false, "Response union should not be nullable")
            }

        // Union should list two children by their concrete aliases
        union.children.size.assertEq(2, "Union should have 2 children")
        val childNames = union.children.map { it.name }.sorted()
        childNames.assertEq(
            listOf("ExtractorTestUnionsUnionOk", "ExtractorTestUnionsUnionUhoh"),
            "Union children should be Ok and Uhoh"
        )

        // For each child there should be an object alias; for bare variants, only the discriminator field exists
        val discriminator = union.discriminatorField
        listOf(
            "ExtractorTestUnionsUnionOk" to "Ok",
            "ExtractorTestUnionsUnionUhoh" to "Uhoh"
        ).forEach { (alias, discVal) ->
            val obj = extraction.types.firstOrNull { it.name == alias }
                .assertNonNull("Expected child object '$alias'")
                .assertType<TsType.Object>("Child alias should be an object type")
            val field = obj.fields[discriminator].assertNonNull("Discriminator field should exist on '$alias'")
            field.optional.assertEq(false, "Discriminator should not be optional on '$alias'")
            field.nullable.assertEq(false, "Discriminator should not be nullable on '$alias'")
            field.type.name.assertEq(
                "\"$discVal\"",
                "Discriminator literal should match child simple name for '$alias'"
            )
        }
    }

    @RequestMapping
    @RestController
    class UnionWithFields {

        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
        sealed interface Union {
            data object Ok : Union {
                val ok = true
            }

            data class Uhoh(
                val error: String? = "uhoh"
            ) : Union
        }

        @PostMapping
        fun post(
            @RequestBody
            req: Union
        ): Union = error("test")
    }

    @Test
    fun unionsWithFields() {
        val extraction = extract<UnionWithFields>()

        val api = extraction.apis.assertSingle("There should be one api extracted")
        val method = api.apiMethods.assertSingle("There should be a single method")

        // Union alias and its children
        val union = extraction.types
            .firstOrNull { it.name == "ExtractorTestUnionWithFieldsUnionUnion" }
            .assertNonNull("The union alias type should be generated with expected name")
            .assertType<TsType.Union>("The union alias should be a TsType.Union")

        // Method types should reference the union alias
        method.requestBodyType.assertNonNull("There should be a request body type")
            .let { rb ->
                rb.name.assertEq(union.name, "Request should be the union alias")
                rb.optional.assertEq(false, "Request union should not be optional")
                rb.nullable.assertEq(false, "Request union should not be nullable")
            }
        method.responseBodyType.assertNonNull("There should be a response body type")
            .let { rsp ->
                rsp.name.assertEq(union.name, "Response should be the union alias")
                rsp.optional.assertEq(false, "Response union should not be optional")
                rsp.nullable.assertEq(false, "Response union should not be nullable")
            }

        union.children.size.assertEq(2, "Union should have 2 children")
        val childNames = union.children.map { it.name }.sorted()
        childNames.assertEq(
            listOf("ExtractorTestUnionWithFieldsUnionOk", "ExtractorTestUnionWithFieldsUnionUhoh"),
            "Union children should be Ok and Uhoh"
        )

        // Children should include both discriminator and declared fields
        val discriminator = union.discriminatorField
        // Ok: has val ok = true
        run {
            val obj = extraction.types.firstOrNull { it.name == "ExtractorTestUnionWithFieldsUnionOk" }
                .assertNonNull("Expected child object 'ExtractorTestUnionWithFieldsUnionOk'")
                .assertType<TsType.Object>("Child alias should be an object type")
            val disc = obj.fields[discriminator].assertNonNull("Discriminator field should exist on Ok")
            disc.optional.assertEq(false, "Discriminator should not be optional on Ok")
            disc.nullable.assertEq(false, "Discriminator should not be nullable on Ok")
            disc.type.name.assertEq("\"Ok\"", "Discriminator literal should be 'Ok'")
            val okField = obj.fields["ok"].assertNonNull("Ok should contain 'ok' field")
            okField.optional.assertEq(false, "ok should not be optional")
            okField.nullable.assertEq(false, "ok should not be nullable")
            okField.type.name.assertEq("boolean", "ok should be boolean")
        }
        // Uhoh: has val error: String? = "uhoh"
        run {
            val obj = extraction.types.firstOrNull { it.name == "ExtractorTestUnionWithFieldsUnionUhoh" }
                .assertNonNull("Expected child object 'ExtractorTestUnionWithFieldsUnionUhoh'")
                .assertType<TsType.Object>("Child alias should be an object type")
            val disc = obj.fields[discriminator].assertNonNull("Discriminator field should exist on Uhoh")
            disc.optional.assertEq(false, "Discriminator should not be optional on Uhoh")
            disc.nullable.assertEq(false, "Discriminator should not be nullable on Uhoh")
            disc.type.name.assertEq("\"Uhoh\"", "Discriminator literal should be 'Uhoh'")
            val err = obj.fields["error"].assertNonNull("Uhoh should contain 'error' field")
            err.optional.assertEq(true, "error should be optional due to default")
            err.nullable.assertEq(true, "error should be nullable by type")
            err.type.name.assertEq("string", "error should be string type")
        }
    }

    @RequestMapping
    @RestController
    class UnionWithGenerics {
        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
        sealed interface Union<T> {
            val shared: T

            data class Ok<T>(
                override val shared: T,
                val ok: Boolean = true
            ) : Union<T>

            data class Error<T>(
                override val shared: T,
                val message: String
            ) : Union<T>
        }

        @PostMapping
        fun post(
            @RequestBody
            req: Union<String>
        ): Union<Int> = error("test")
    }

    @Test
    fun unionWithGenerics() {
        val extraction = extract<UnionWithGenerics>()

        val api = extraction.apis.assertSingle("There should be one api extracted")
        val method = api.apiMethods.assertSingle("There should be a single method")

        extraction.types
            .firstOrNull { it.name == "ExtractorTestUnionWithGenericsUnion<T>" }
            .assertNonNull("The interface type should be generated with expected name")
            .assertType<TsType.Object>("The union alias should be a TsType.Object")


        val union = extraction.types
            .firstOrNull { it.name == "ExtractorTestUnionWithGenericsUnionUnion<T>" }
            .assertNonNull("The union alias type should be generated with expected name")
            .assertType<TsType.Union>("The union alias should be a TsType.Object")

        method.requestBodyType.assertNonNull("There should be a request body type")
            .let { req ->
                req.name.assertEq(union.name, "Request should be the union alias")
                req.generics.assertSingleKey("T", "Should have generic parameter T")
                    .name.assertEq("string", "Request should use String for T")
            }

        method.responseBodyType.assertNonNull("There should be a response body type")
            .let { resp ->
                resp.name.assertEq(union.name, "Response should be the union alias")
                resp.generics.assertSingleKey("T", "Should have generic parameter T")
                    .name.assertEq("number", "Response should use number for T")
            }

        union.children.size.assertEq(2, "Union should have 2 children")
        val discriminator = union.discriminatorField

        extraction.types.filter { it.name.contains("UnionWithGenericsUnion") }
            .forEach { type ->
                when (type.name) {
                    "ExtractorTestUnionWithGenericsUnionOk" -> {
                        type.assertType<TsType.Object>("Ok should be object type")
                            .let { obj ->
                                obj.fields[discriminator].assertNonNull("Should have discriminator")
                                    .type.name.assertEq("\"Ok\"", "Should have Ok discriminator")
                                // shared is inherited from Union interface, not in fields directly
                                // Instead, child should have intersection with parent interface
                                obj.intersections.size.assertEq(1, "Ok should have 1 intersection (parent interface)")
                                obj.fields["ok"].assertNonNull("Should have ok field")
                                    .let { ok ->
                                        ok.optional.assertEq(true, "ok should be optional")
                                        ok.type.name.assertEq("boolean", "ok should be boolean")
                                    }
                            }
                    }

                    "ExtractorTestUnionWithGenericsUnionError" -> {
                        type.assertType<TsType.Object>("Error should be object type")
                            .let { obj ->
                                obj.fields[discriminator].assertNonNull("Should have discriminator")
                                    .type.name.assertEq("\"Error\"", "Should have Error discriminator")
                                // shared is inherited from Union interface, not in fields directly
                                obj.intersections.size.assertEq(
                                    1,
                                    "Error should have 1 intersection (parent interface)"
                                )
                                obj.fields["message"].assertNonNull("Should have message field")
                                    .type.name.assertEq("string", "message should be string")
                            }
                    }
                }
            }
    }
}