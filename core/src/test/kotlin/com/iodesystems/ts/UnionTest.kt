package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

class UnionTest {

    @RestController
    @RequestMapping
    class Inheritence {
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
        val emitter = emitter(Inheritence::class)
        val result = emitter.ts().content()

        result.assertContains(
            fragment = """
            export type UnionTestInheritenceIModel = {
              name: string
            }
            """.trimIndent(),
            "IModel should be emitted"
        )
        result.assertContains(
            fragment = """
            export type UnionTestInheritenceModel = UnionTestInheritenceIModel & {
              b: string
            }
            """.trimIndent(),
            "Model should be emitted"
        )

        result.assertContains(
            """
            export type UnionTestInheritenceModelA = UnionTestInheritenceModel & {
              "@type": "A"
            }
        """.trimIndent(),
            "A variant should be emitted"
        )
        result.assertContains(
            """
            export type UnionTestInheritenceModelUnion = UnionTestInheritenceModel & (UnionTestInheritenceModelA | UnionTestInheritenceModelB)
        """.trimIndent(),
            "Union should contain A | B"
        )
        result.assertContains(
            """
              post(req: UnionTestInheritenceModelUnion): Promise<void> {
        """.trimIndent(),
            "POST method should accept UnionTestInheritenceModelUnion"
        )


    }
}