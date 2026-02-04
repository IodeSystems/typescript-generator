package com.iodesystems.ts.emitter

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.Asserts.assertNotContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.junit.Ignore
import org.springframework.web.bind.annotation.*
import kotlin.test.Test

class EmitterTest {

    @Test
    fun headersAreEmitted() {
        val emitter = emitter(Simple::class) { eslintDisable() }
        val result = emitter.ts().content(includeLib = true)
        result.assertContains(
            "/* eslint-disable @typescript-eslint/no-explicit-any */",
            "should include eslint disable for no-explicit-any at top of files"
        )
        result.assertContains(
            "/* eslint-disable @typescript-eslint/no-unused-vars */",
            "should include eslint disable for no-unused-vars at top of files"
        )
    }


    @RestController
    @RequestMapping
    class Simple {
        @PostMapping
        fun withRequest(
            @RequestBody
            req: Boolean
        ): Unit = error("stub")

        @GetMapping
        fun withResponse(
        ): Boolean = error("stub")

        @PostMapping
        fun withRequestAndResponse(
            @RequestBody
            req: Boolean
        ): Boolean = error("stub")
    }

    @Test
    fun simplePrimitive() {
        val emitter = emitter(Simple::class)
        val result = emitter.ts().content()
        result.assertContains(
            """
            |  withRequest(req: boolean): AbortablePromise<void> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "POST",
            |      headers: {'Content-Type': 'application/json'},
            |      body: JSON.stringify(req)
            |    }).then(r=>{})
            |  }
            """.trimMargin(), "withRequest"
        )

        result.assertContains(
            """
            |  withResponse(): AbortablePromise<boolean> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "GET"
            |    }).then(r=>r.json())
            |  }
            """.trimMargin(), "request"
        )
        result.assertContains(
            """
            |  withRequestAndResponse(req: boolean): AbortablePromise<boolean> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "POST",
            |      headers: {'Content-Type': 'application/json'},
            |      body: JSON.stringify(req)
            |    }).then(r=>r.json())
            |  }
            """.trimMargin(), "request"
        )
    }

    class JsonCreatorAliasTypes {
        class ByteString private constructor(
            private val url: String
        ) {
            companion object {
                @JvmStatic
                @JsonCreator
                fun fromBase64Url(base64url: String): ByteString {
                    return ByteString(base64url.split("=")[0])
                }
            }
        }

        @RestController
        @RequestMapping
        class Api {
            @GetMapping
            fun get(): ByteString = error("stub")
        }
    }

    @Test
    fun emits_type_alias_for_single_param_json_creator() {
        val emitter = emitter(JsonCreatorAliasTypes.Api::class) {
            mapType(JsonCreatorAliasTypes.ByteString::class, "string")
        }
        val result = emitter.ts().content()
        // Expect a type alias like: export type ByteString = string
        result.assertContains(
            "export type EmitterTestJsonCreatorAliasTypesByteString = string",
            "ByteString alias should exist"
        )
        result.assertContains(
            "get(): AbortablePromise<EmitterTestJsonCreatorAliasTypesByteString> {",
            "ByteString function parameter should exist"
        )
    }

    @RestController
    @RequestMapping
    class SimpleType {
        data class Foo(val bar: String)

        @PostMapping
        fun withRequest(
            @RequestBody
            req: Foo
        ): Unit = error("stub")

        @GetMapping
        fun withResponse(
        ): Foo = error("stub")

        @PostMapping
        fun withRequestAndResponse(
            @RequestBody
            req: Foo
        ): Foo = error("stub")
    }

    @Test
    fun simpleType() {
        val emitter = emitter(SimpleType::class)
        val result = emitter.ts().content()
        result.assertContains(
            """
            export type EmitterTestSimpleTypeFoo = {
              bar: string
            }
            """.trimIndent(),
            "type should exist"
        )
        result.assertContains(
            """
            |  withRequest(req: EmitterTestSimpleTypeFoo): AbortablePromise<void> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "POST",
            |      headers: {'Content-Type': 'application/json'},
            |      body: JSON.stringify(req)
            |    }).then(r=>{})
            |  }
            """.trimMargin(), "withRequest"
        )

        result.assertContains(
            """
            |  withResponse(): AbortablePromise<EmitterTestSimpleTypeFoo> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "GET"
            |    }).then(r=>r.json())
            |  }
            """.trimMargin(), "request"
        )
        result.assertContains(
            """
            |  withRequestAndResponse(req: EmitterTestSimpleTypeFoo): AbortablePromise<EmitterTestSimpleTypeFoo> {
            |    return fetchInternal(this.opts, "/", {
            |      method: "POST",
            |      headers: {'Content-Type': 'application/json'},
            |      body: JSON.stringify(req)
            |    }).then(r=>r.json())
            |  }
            """.trimMargin(), "request"
        )
    }

    @RestController
    @RequestMapping("herp/derp")
    class KitchenSink {

        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
        sealed interface Union {
            data object Ok : Union
            data object Uhoh : Union
        }

        interface IContainer<Q> {
            val item: Q
        }

        open class Container<T>(
            override val item: T
        ) : IContainer<T>

        class Request<A, B>(
            item: A,
            val items: List<B>
        ) : Container<A>(item)

        data class Other(val value: String)

        @PostMapping
        fun post(
            @RequestBody
            req: Map<String, Request<String, Int>>
        ): Union = error("test")

        data object Get {
            data class Response(
                val items: List<String> = emptyList(),
            )
        }

        @GetMapping
        fun get(): List<Get.Response?> = error("test")

        @GetMapping("/{id}")
        fun path(
            @PathVariable id: Long
        ) {
        }

        data class SearchQuery(
            val q: String,
            @RequestParam(required = false)
            val limit: Int?
        )

        @GetMapping("/search")
        fun search(
            @RequestParam("q") q: String,
            @RequestParam(required = false) limit: Int?
        ): List<Int> = error("test")

        @PostMapping("/optional")
        fun optional(
            @RequestBody req: Other?
        ) {
        }
    }

    @Test
    fun kitchenSink() {
        val emitter = emitter(KitchenSink::class)
        val result = emitter.ts().content()
        result.assertContains(
            "post(req: Record<string,EmitterTestKitchenSinkRequest<string,number>>): AbortablePromise<EmitterTestKitchenSinkUnionUnion> {",
            "generics should be resolved here"
        )
        result.assertContains(
            "get(): AbortablePromise<Array<EmitterTestKitchenSinkGetResponse | null>> {",
            "generics should be resolved here"
        )

        // Path parameter handling
        result.assertContains(
            "path(path: { id: string | number }): AbortablePromise<void> {",
            "should generate a path param object with string | number for numeric id"
        )
        result.assertContains(
            ".replace(\"{id}\", String(path.id))",
            "should replace {id} placeholder with provided path param"
        )

        // Query parameter handling + flattenQueryParams usage
        result.assertContains(
            "search(query: EmitterTestKitchenSinkSearchQuery): AbortablePromise<Array<number>> {",
            "should generate named query type and return array of numbers"
        )
        result.assertContains(
            "return fetchInternal(this.opts, flattenQueryParams(\"herp/derp/search\", query, null), {",
            "should call flattenQueryParams when query params exist"
        )

        // Optional/nullable request body handling (nullable body still serializes with headers)
        result.assertContains(
            "optional(req: EmitterTestKitchenSinkOther | null): AbortablePromise<void> {",
            "nullable body type should be reflected in signature"
        )
        result.assertContains(
            "headers: {'Content-Type': 'application/json'},",
            "methods with request bodies should set JSON content headers"
        )

        // Type emissions: union alias and nested DTOs
        result.assertContains(
            "export type EmitterTestKitchenSinkUnionUnion = EmitterTestKitchenSinkUnion & (EmitterTestKitchenSinkUnionOk | EmitterTestKitchenSinkUnionUhoh)",
            "union type should flatten into a literal union"
        )
        result.assertContains(
            "export type EmitterTestKitchenSinkGetResponse = {\n  items?: Array<string> | undefined\n}",
            "nested response DTO with defaulted list should be optional and include undefined in type"
        )
    }

    // Empty generic stub: a generic interface with no fields that appears as a return type
    interface EmptyWrapper<T>

    @RestController
    @RequestMapping
    class EmptyGenericStubApi {
        @GetMapping
        fun get(): EmptyWrapper<String> = error("stub")
    }

    @Test
    fun emptyGenericStubDropsUnusedGenerics() {
        val emitter = emitter(EmptyGenericStubApi::class)
        val result = emitter.ts().content()
        result.assertContains(
            "export type EmitterTestEmptyWrapper = {\n}",
            "empty generic stub should have generics stripped from declaration"
        )
        result.assertNotContains(
            "EmptyWrapper<",
            "references to empty generic stub should not include generic params"
        )
    }
}