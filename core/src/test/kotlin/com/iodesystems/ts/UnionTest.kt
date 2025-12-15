package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import com.iodesystems.ts.lib.getExternalClasspath
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

class UnionTest {

    @Test
    fun object_unions() {
        val emitter = emitter {
            packageAccept("com.external.unions.ObjectUnions")
            classPathUrls { getExternalClasspath() }
        }
        val result = emitter.ts().content()
//        println(result)
    }


    @RestController
    @RequestMapping
    class Inheritance {
        interface IModel {
            val name: String

        }

        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
        sealed interface Model : IModel {
            val b: String get() = "super!"

            data object A : Model {
                override val name: String = "A"
            }

            data class B(override val name: String = "B") : Model

        }

        @PostMapping
        fun post(
            @RequestBody
            req: Model
        ) = Unit
    }

    @Test
    fun union_inheritance() {
        val emitter = emitter(Inheritance::class)
        val result = emitter.ts().content()

        result.assertContains(
            fragment = """
            export type UnionTestInheritanceIModel = {
              name: string
            }
            """.trimIndent(),
            "IModel should be emitted"
        )
        result.assertContains(
            fragment = """
            export type UnionTestInheritanceModel = UnionTestInheritanceIModel & {
              b: string
            }
            """.trimIndent(),
            "Model should be emitted"
        )

        result.assertContains(
            """
            export type UnionTestInheritanceModelA = UnionTestInheritanceModel & {
              "@type": "A"
            }
        """.trimIndent(),
            "A variant should be emitted"
        )
        result.assertContains(
            """
            export type UnionTestInheritanceModelUnion = UnionTestInheritanceModel & (UnionTestInheritanceModelA | UnionTestInheritanceModelB)
        """.trimIndent(),
            "Union should contain A | B"
        )
        result.assertContains(
            """
              post(req: UnionTestInheritanceModelUnion): Promise<void> {
        """.trimIndent(),
            "POST method should accept UnionTestInheritanceModelUnion"
        )
    }
}