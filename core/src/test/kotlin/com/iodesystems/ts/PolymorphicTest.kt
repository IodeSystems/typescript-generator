package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RequestMapping
@RestController
class Poly {

    class Container<T>(val value: T)
    interface IContainer<A, B> {
        val a: A
        val b: B
    }

    class Response(
        override val a: String, override val b: Boolean
    ) : IContainer<String, Boolean>

    @PostMapping
    fun post(@RequestBody req: Container<String>): Response = error("testing")
}

class PolymorphicTest {
    @Test
    fun emitsPolymorphicBodiesReturnsAndIntermediateTypes() {
        val out = TypeScriptGenerator.build {
            it
                .includeApi { name -> name == (Poly::class.qualifiedName!!) }
                .outputDirectory("./tmp")
        }.generate()

        val ts = out.tsApis()

        // Core expectations only; ignore exact comment/reference formatting and ordering
        kotlin.test.assertTrue(ts.contains("post(req: PolyContainer<string>): Promise<PolyResponse>"))
        kotlin.test.assertTrue(ts.contains("type PolyContainer<T> = {\n  value: T\n}"))
        kotlin.test.assertTrue(ts.contains("type PolyIContainer<A,B> = {\n  a: A\n  b: B\n}"))
        kotlin.test.assertTrue(ts.contains("type PolyResponse = {\n  a: string\n  b: boolean\n} & PolyIContainer<string, boolean>"))

        // Full strict expectation (final target)
        kotlin.test.assertEquals(
            $$"""
                export class Poly {
                  constructor(private opts: ApiOptions = {}) {}
                  post(req: PolyContainer<string>): Promise<PolyResponse> {
                    return fetchInternal(this.opts, "/", {
                      method: "POST",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                }

                /**
                 * JVM: com.iodesystems.ts.Poly$Container
                 * Referenced by:
                 * - com.iodesystems.ts.Poly.post
                 */
                type PolyContainer<T> = {
                  value: T
                }

                /**
                 * JVM: com.iodesystems.ts.Poly$IContainer
                 * Referenced by:
                 * - com.iodesystems.ts.Poly$Response
                 */
                type PolyIContainer<A,B> = {
                  a: A
                  b: B
                }

                /**
                 * JVM: com.iodesystems.ts.Poly$Response
                 * Referenced by:
                 * - com.iodesystems.ts.Poly.post
                 */
                type PolyResponse = {
                  a: string
                  b: boolean
                } & PolyIContainer<string, boolean>
            """.trimIndent(),
            ts
        )
    }
}