package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/rec2/")
class IndirectRecursiveApi {

    data class A(
        val b: B?
    )

    data class B(
        val a: A?
    )

    @GetMapping
    fun get(): A = error("test")
}

class IndirectRecursiveTypeTest {

    @Test
    fun indirectRecursiveShape() {
        val em = emitter(IndirectRecursiveApi::class)
        val ts = em.ts().content()

        ts.assertContains(
            fragment = """
                export type IndirectRecursiveApiA = {
                  b: IndirectRecursiveApiB | null
                }
            """.trimIndent(),
            why = "A has a nullable reference to B"
        )

        ts.assertContains(
            fragment = """
                export type IndirectRecursiveApiB = {
                  a: IndirectRecursiveApiA | null
                }
            """.trimIndent(),
            why = "B has a nullable reference back to A"
        )

        ts.assertContains(
            fragment = """
                export class IndirectRecursiveApi {
                  constructor(private opts: ApiOptions = {}) {}
                  get(): Promise<IndirectRecursiveApiA> {
            """.trimIndent(),
            why = "API method should return A which indirectly references B"
        )
    }
}
