package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Ignore
import kotlin.test.Test

@RestController
@RequestMapping("/api/jackson")
class JacksonApi {
    data class Payload(
        @param:JsonProperty("renamedViaParam")
        val originalParam: String,

        @field:JsonProperty("renamedViaField")
        val originalField: String,

        @get:JsonProperty("renamedViaGetter")
        val originalGetter: String,

        // No JsonProperty value, only alias → adapter should pick first alias as name
        @field:JsonAlias("aka")
        val aliasOnly: String,

        val code: MyEnum,
    )

    @RequestMapping("go")
    fun get(): Payload = error("not used in tests")
}

enum class MyEnum() {
    A,
    B;
}

class JacksonTest {
    @Test
    @Ignore
    fun verifiesJsonPropertyAliasAndJsonValueEnum() {
        val em = emitter<JacksonApi> { outputDirectory("./tmp") }
        val content = em.ts().content()

        // Payload type with Jackson-renamed fields and enum as string union
        content.assertContains(
            fragment = """
                export type JacksonApiPayload = {
                  renamedViaParam: string
                  renamedViaField: string
                  renamedViaGetter: string
                  aka: string
                  code: 'A' | 'B'
                }
            """.trimIndent(),
            why = "JsonProperty/JsonAlias should rename fields; enums emitted as string unions"
        )

        // get() method block
        content.assertContains(
            fragment = """
                get(): Promise<JacksonApiPayload> {
                  return fetchInternal(this.opts, "/api/jackson/go", {
                    method: "GET"
                  }).then(r=>r.json())
                }
            """.trimIndent(),
            why = "GET should return the renamed payload type from '/api/jackson/go'"
        )
    }
}
