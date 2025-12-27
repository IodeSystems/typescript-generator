package com.iodesystems.ts.emitter

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import kotlin.test.Test

class SealedResponseEmitterTest {

    @RestController
    @RequestMapping
    class SampleApi {
        data class Add(val a: Int, val b: Int) {
            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
            sealed interface Response {
                data class Success(
                    val result: Int,
                    val at: OffsetDateTime? = null
                ) : Response

                data class Failure(val error: String) : Response
            }
        }

        @PostMapping
        fun add(@RequestBody user: Add): Add.Response = Add.Response.Failure("Test implementation!")

        data class Ping(val message: String) {
            @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
            sealed interface Response {
                data class Pong(
                    val message: String,
                    val at: OffsetDateTime? = null
                ) : Response
            }
        }

        @PostMapping("/ping")
        fun ping(@RequestBody ping: Ping): Ping.Response = Ping.Response.Pong(message = "pong")
    }

    @Test
    fun sealed_responses_are_not_conflated_and_fields_are_preserved() {
        val emitter = emitter(SampleApi::class)
        val result = emitter.ts().content()

        // Ensure method signatures use distinct union types per method
        result.assertContains(
            "add(req: SealedResponseEmitterTestSampleApiAdd): AbortablePromise<SealedResponseEmitterTestSampleApiAddResponseUnion>",
            "'add' should return its own response union type"
        )
        result.assertContains(
            "ping(req: SealedResponseEmitterTestSampleApiPing): AbortablePromise<SealedResponseEmitterTestSampleApiPingResponseUnion>",
            "'ping' should return its own response union type"
        )

        // Ensure unions contain the correct members (no cross-contamination)
        result.assertContains(
            "export type SealedResponseEmitterTestSampleApiAddResponseUnion = SealedResponseEmitterTestSampleApiAddResponse & (SealedResponseEmitterTestSampleApiAddResponseFailure | SealedResponseEmitterTestSampleApiAddResponseSuccess)",
            "AddResponseUnion should contain Failure | Success"
        )
        result.assertContains(
            "export type SealedResponseEmitterTestSampleApiPingResponseUnion = SealedResponseEmitterTestSampleApiPingResponse & (SealedResponseEmitterTestSampleApiPingResponsePong)",
            "PingResponseUnion should contain only Pong"
        )

        // Ensure discriminator AND fields are present for each variant
        result.assertContains(
            "export type SealedResponseEmitterTestSampleApiAddResponseFailure = SealedResponseEmitterTestSampleApiAddResponse & {",
            "Failure variant type should be defined"
        )
        result.assertContains(
            "\n  \"@type\": \"Failure\"\n  error: string\n",
            "Failure should include discriminator and error field"
        )

        result.assertContains(
            "export type SealedResponseEmitterTestSampleApiAddResponseSuccess = SealedResponseEmitterTestSampleApiAddResponse & {",
            "Success variant type should be defined"
        )
        result.assertContains(
            "\n  \"@type\": \"Success\"\n",
            "Success should include discriminator"
        )
        result.assertContains(
            "\n  result: number\n",
            "Success should include result field"
        )
        // Optional/nullable timestamp is emitted and marked optional (type may vary, do not assert exact type)
        result.assertContains(
            "\n  at?: ",
            "Timestamp field should be optional in variants with default null"
        )

        result.assertContains(
            """
                export type SealedResponseEmitterTestSampleApiPingResponsePong = SealedResponseEmitterTestSampleApiPingResponse & {
                  "@type": "Pong"
                  at?: OffsetDateTime | null | undefined
                  message: string
                }
            """.trimIndent(),
            "Pong variant type should be defined"
        )
        result.assertContains(
            "\n  \"@type\": \"Pong\"\n",
            "Pong should include discriminator"
        )
        result.assertContains(
            "\n  message: string\n",
            "Pong should include message field"
        )
    }
}
