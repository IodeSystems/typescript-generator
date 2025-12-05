package com.iodesystems.ts

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
        val em = emitter<Primitive>()
        val content = em.ts().content()
        content.assertContains(
            fragment = """
                post(req: string): Promise<boolean | null> {
                  return fetchInternal(this.opts, "/", {
                    method: "POST",
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify(req)
                  }).then(r=>r.json())
                }
            """.trimIndent(),
            why = "Primitive request body and nullable boolean response should be encoded/decoded properly"
        )
    }
}