package com.iodesystems.ts.emitter

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.Config
import com.iodesystems.ts.Emitter
import com.iodesystems.ts.Scanner
import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.*
import kotlin.test.Ignore
import kotlin.test.Test

class EmitterTest {

    companion object {
        inline fun <reified T> emitter(
            crossinline fn: (Config.Builder.() -> Config.Builder) = { this }
        ): Emitter {
            val config = Config().build { includeApi(T::class).fn() }
            val scan = Scanner(config).scan()
            val apiRegistry = config.apiExtractor().extract(scan)
            val extraction = JvmExtractor(config).buildFromRegistry(scan, apiRegistry)
            return Emitter(config, extraction)
        }

        fun Emitter.Output.content(
            includeLib: Boolean = false
        ): String {
            val sb = StringBuilder()
            files.forEach {
                sb.append(it.file.name + ":\n")
                sb.append("=========\n")
                sb.append(it.content)
                sb.append("\n=========\n")
            }
            return sb.toString().let { s ->
                if (includeLib) s
                else s.replace(Emitter.lib(), "")
            }
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
        val emitter = emitter<Simple>()
        val result = emitter.ts().content()
        result.assertContains(
            """
            export class EmitterTestSimple {
              constructor(private opts: ApiOptions = {}) {}
              post(req: boolean): Promise<void> {
                return fetchInternal(this.opts, "/", {
                  method: "POST",
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify(req)
                }).then(r=>{})
              }
            }
        """.trimIndent(), "missing"
        )
    }


    @RestController
    @RequestMapping
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
        val emitter = emitter<KitchenSink>()
        val result = emitter.ts().content()
        result.assertContains(
            "post(req: Record<string,EmitterTestKitchenSinkRequest<string,number>>): Promise<EmitterTestKitchenSinkUnionUnion> {",
            "generics should be resolved here"
        )
        result.assertContains(
            "get(): Promise<Array<EmitterTestKitchenSinkGetResponse | null>> {",
            "generics should be resolved here"
        )

        // Path parameter handling
        result.assertContains(
            "path(path: { id: string | number }): Promise<void> {",
            "should generate a path param object with string | number for numeric id"
        )
        result.assertContains(
            ".replace(\"{id}\", String(path.id))",
            "should replace {id} placeholder with provided path param"
        )

        // Query parameter handling + flattenQueryParams usage
        result.assertContains(
            "search(query: EmitterTestKitchenSinkSearchQuery): Promise<Array<number>> {",
            "should generate named query type and return array of numbers"
        )
        result.assertContains(
            "return fetchInternal(this.opts, flattenQueryParams(\"/search\", query, null), {",
            "should call flattenQueryParams when query params exist"
        )

        // Optional/nullable request body handling (nullable body still serializes with headers)
        result.assertContains(
            "optional(req: EmitterTestKitchenSinkOther | null): Promise<void> {",
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
}