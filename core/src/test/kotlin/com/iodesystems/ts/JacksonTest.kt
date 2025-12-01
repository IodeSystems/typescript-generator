package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun verifiesJsonPropertyAliasAndJsonValueEnum() {
        val output = TypeScriptGenerator.build {
            it
                .includeApi { name -> name == (JacksonApi::class.qualifiedName!!) }
                .outputDirectory("./tmp")
        }.generate()

        val ts = output.tsApis()

        assertEquals(
            $$"""
            export class JacksonApi {
              constructor(private opts: ApiOptions = {}) {}
              get(): Promise<JacksonApiPayload> {
                return fetchInternal(this.opts, "/api/jackson/go", {
                  method: "GET"
                }).then(r=>r.json())
              }
            }

            /**
             * JVM: com.iodesystems.ts.JacksonApi$Payload
             * Referenced by:
             * - com.iodesystems.ts.JacksonApi.get
             */
            type JacksonApiPayload = {
              renamedViaParam: string
              renamedViaField: string
              renamedViaGetter: string
              aka: string
              code: 'A' | 'B'
            }
        """.trimIndent(), ts
        )
    }
}
