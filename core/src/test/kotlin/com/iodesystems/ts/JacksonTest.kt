package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
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

    @PostMapping("param")
    fun post(@RequestBody req: JacksonParam): JacksonParam = req
}

enum class MyEnum() {
    A,
    B;
}

class JacksonTest {
    @Test
    fun verifiesJsonPropertyAliasAndJsonValueEnum() {
        val em = emitter(JacksonApi::class) { outputDirectory("./tmp") }
        val content = em.ts().content()

        // Payload type with Jackson-renamed fields and enum as string union
        content.assertContains(
            fragment = """
                export type JacksonApiPayload = {
                  renamedViaParam: string
                  renamedViaField: string
                  renamedViaGetter: string
                  aka: string
                  code: MyEnum
                }
            """.trimIndent(),
            why = "JsonProperty/JsonAlias should rename fields; enums emitted as string unions"
        )

        // get() method block
        content.assertContains(
            fragment = """
              |  get(): Promise<JacksonApiPayload> {
              |    return fetchInternal(this.opts, "/api/jackson/go", {
              |      method: "GET"
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "GET should return the renamed payload type from '/api/jackson/go'"
        )
    }

    @Test
    fun verifiesJacksonParamOptionality() {
        val em = emitter(JacksonApi::class) { outputDirectory("./tmp") }
        val content = em.ts().content()

        // Type for JacksonParam should have optional fields derived from @JsonProperty(required=false)
        content.assertContains(
            fragment = """
                export type JacksonParam = {
                  fOptional?: string | undefined
                  fOptionalNullable?: string | undefined
                }
            """.trimIndent(),
            why = "@JsonProperty(required=false) should mark fields as optional in TypeScript"
        )

        // Post method should accept JacksonParam as request body and return it
        content.assertContains(
            fragment = """
              |  post(req: JacksonParam): Promise<JacksonParam> {
              |    return fetchInternal(this.opts, "/api/jackson/param", {
              |      method: "POST",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin("|"),
            why = "POST should use JacksonParam with request body and return it"
        )
    }
}
