package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

@RestController
@RequestMapping
class Primitive {

    @PostMapping
    fun post(
        @RequestBody req: String
    ): Boolean? = null
}

class PrimitiveRequestTest {

    @Test
    @Ignore
    fun testPrimitiveRequestResponses() {
        val out = TypeScriptGenerator.build {
            it.includeApi<Primitive>()
        }
            .generate()
        out.extraction.apis.let {
            assertEquals(1, it.size)
        }
        out.tsApis().let {
            assertEquals(
                """
                export class Primitive {
                  constructor(private opts: ApiOptions = {}) {}
                  post(req: string): Promise<boolean | null> {
                    return fetchInternal(this.opts, "/", {
                      method: "POST",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                }
            """.trimIndent(), it
            )
        }
    }
}