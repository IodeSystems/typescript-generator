package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals


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
        val out = TypeScriptGenerator.build {
            it.includeApi { it.endsWith("OptionalFields") }
        }.generate()

        out.extraction.apis.let {
            assertEquals(1, it.size)
        }
        // typeRegistry removed in new model

        out.tsApis().let {
            assertEquals(
                $$"""
                export class OptionalFields {
                  constructor(private opts: ApiOptions = {}) {}
                  ping(req: OptionalFieldsPing): Promise<OptionalFieldsResponse> {
                    return fetchInternal(this.opts, "/test/", {
                      method: "POST",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                }
                
                /**
                 * JVM: com.iodesystems.ts.OptionalFields$Ping
                 * Referenced by:
                 * - com.iodesystems.ts.OptionalFields.ping
                 */
                type OptionalFieldsPing = {
                  message?: string | null
                  requiredish?: boolean
                }
                
                /**
                 * JVM: com.iodesystems.ts.OptionalFields$Response
                 * Referenced by:
                 * - com.iodesystems.ts.OptionalFields.ping
                 */
                type OptionalFieldsResponse = {
                  message?: string
                }
            """.trimIndent(), it
            )
        }
    }
}