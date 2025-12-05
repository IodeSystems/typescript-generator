package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.Asserts.assertEq
import com.iodesystems.ts.lib.Asserts.assertNonNull
import com.iodesystems.ts.lib.Asserts.assertType
import com.iodesystems.ts.model.TsType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test


@RestController
@RequestMapping("/test/")
class OptionalFields {

    data class Ping(
        val message: String? = "ping",
        val requiredish: Boolean = true

    )

    data class Response(
        val message: String = "pong"
    )

    @PostMapping
    fun ping(
        @RequestBody req: Ping
    ): Response = Response(req.message ?: "ping")
}

class OptionalFieldsTest {

    @Test
    fun testOptionalFields() {
        val em = emitter<OptionalFields>()
        em.extraction.types.let { types ->

            types.firstOrNull { t ->
                t.tsName == "OptionalFieldsPing"
            }
                .assertNonNull("OptionalFieldsPing should be present in types")
                .assertType<TsType.Object>("OptionalFieldsPing should be an object")
                .let { t ->
                    t.fields["message"]
                        .assertNonNull("OptionalFieldsPing should have a message field")
                        .let { f ->
                            f.optional.assertEq(true, "message should be optional")
                            f.nullable.assertEq(true, "message should be nullable")
                        }

                    t.fields["requiredish"]
                        .assertNonNull("OptionalFieldsPing should have a requiredish field")
                        .let { f ->
                            f.optional.assertEq(true, "requiredish should be optional")
                            f.nullable.assertEq(false, "requiredish should be nullable")
                        }
                }
        }

        val content = em.ts().content()
        content.assertContains(
            fragment = """
                export type OptionalFieldsPing = {
                  message?: string | null | undefined
                  requiredish?: boolean | undefined
                }
            """.trimIndent(),
            why = "Ping has a nullable String and a defaulted Boolean; both should be optional in TS"
        )

        content.assertContains(
            fragment = """
                export type OptionalFieldsResponse = {
                  message?: string | undefined
                }
            """.trimIndent(),
            why = "Response’s Kotlin default makes the field optional in emitted TypeScript"
        )
    }
}