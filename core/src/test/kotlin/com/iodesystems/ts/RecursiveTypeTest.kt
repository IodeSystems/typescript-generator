package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.junit.Ignore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/rec/")
class RecursiveTypesApi {

    data class Node(
        val left: Node?,
        val right: Node?
    )

    @GetMapping
    fun get(): Node = error("test")
}

class RecursiveTypeTest {

    @Test
    fun recursiveNodeShape() {
        val em = emitter(RecursiveTypesApi::class)
        val ts = em.ts().content()

        ts.assertContains(
            fragment = """
                export type RecursiveTypesApiNode = {
                  left: RecursiveTypesApiNode | null
                  right: RecursiveTypesApiNode | null
                }
            """.trimIndent(),
            why = "Recursive Node should reference itself for left/right, allowing nulls"
        )

        ts.assertContains(
            fragment = """
                export class RecursiveTypesApi {
                  constructor(private opts: ApiOptions = {}) {}
                  get(): Promise<RecursiveTypesApiNode> {
            """.trimIndent(),
            why = "API method should return the recursive Node type"
        )
    }

    @RestController
    @RequestMapping
    class Generics {
        data class Tree<T>(
            val value: T,
            val children: List<Tree<T>>
        )

        @GetMapping("/tree")
        fun getTree(): Tree<Tree<String>> = error("test")
    }

    @Test
    fun recursiveGenerics() {
        val em = emitter(Generics::class)
        val ts = em.ts().content()
        println(ts)
    }
}
