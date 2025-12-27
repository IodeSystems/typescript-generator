package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.junit.Ignore
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
        override val a: String,
        override val b: Boolean
    ) : IContainer<String, Boolean>

    @PostMapping
    fun post(@RequestBody req: Container<String>): Response = error("testing")
}

class PolymorphicTest {
    @Test
    fun emitsPolymorphicBodiesReturnsAndIntermediateTypes() {
        val em = emitter(Poly::class) {
            outputDirectory("./tmp")
        }
        val content = em.ts().content()

        // API method block
        content.assertContains(
            fragment = """
            post(req: PolyContainer<string>): AbortablePromise<PolyResponse> {
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

        // Interface super-type - check that both fields exist (order may vary by JVM)
        content.assertContains(
            fragment = "type PolyIContainer<A,B> = {",
            why = "Response's implemented interface should be emitted as a separate type"
        )
        content.assertContains(
            fragment = "a: A",
            why = "Interface should have 'a' field"
        )
        content.assertContains(
            fragment = "b: B",
            why = "Interface should have 'b' field"
        )

        // Response type intersection (supers-first order in emitter)
        content.assertContains(
            fragment = """
            type PolyResponse = PolyIContainer<string,boolean>
        """.trimIndent(),
            why = "Response should intersect its implemented interface with concrete type arguments"
        )
    }
}