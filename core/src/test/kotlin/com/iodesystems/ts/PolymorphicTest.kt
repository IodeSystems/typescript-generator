package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Ignore
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
    @Ignore
    fun emitsPolymorphicBodiesReturnsAndIntermediateTypes() {
        val em = emitter<Poly> {
            outputDirectory("./tmp")
        }
        val content = em.ts().content()

        // API method block
        content.assertContains(
            fragment = """
            post(req: PolyContainer<string>): Promise<PolyResponse> {
        """.trimIndent(),
            why = "POST method should accept PolyContainer<string> and return PolyResponse"
        )

        // Generic container type definition
        content.assertContains(
            fragment = """
            type PolyContainer<T> = {
              value: T
            }
        """.trimIndent(),
            why = "Simple generic types should be emitted for request bodies"
        )

        // Interface super-type
        content.assertContains(
            fragment = """
            type PolyIContainer<A,B> = {
              a: A
              b: B
            }
        """.trimIndent(),
            why = "Response's implemented interface should be emitted as a separate type"
        )

        // Response type intersection
        content.assertContains(
            fragment = """
            type PolyResponse = {
              a: string
              b: boolean
            } & PolyIContainer<string, boolean>
        """.trimIndent(),
            why = "Response should intersect its implemented interface with concrete type arguments"
        )
    }
}