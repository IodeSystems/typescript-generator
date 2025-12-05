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
    }
}