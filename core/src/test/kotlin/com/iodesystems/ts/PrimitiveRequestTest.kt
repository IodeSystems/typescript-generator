package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping
class Primitive {

    @PostMapping
    fun nullResponse(@RequestBody req: String): Boolean? = null

    @PostMapping
    fun nullRequest(@RequestBody req: String?): Boolean = false
}

class PrimitiveRequestTest {

    @Test
    fun testPrimitiveRequestResponses() {
        val em = emitter(Primitive::class)
        val content = em.ts().content()
        content.assertContains(
            fragment = """
              |  nullResponse(req: string): Promise<boolean | null> {
              |    return fetchInternal(this.opts, "/", {
              |      method: "POST",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin("|"),
            why = "Primitive request body and nullable boolean response should be encoded/decoded properly"
        )
        content.assertContains(
            fragment = """
              |  nullRequest(req: string | null): Promise<boolean> {
              |    return fetchInternal(this.opts, "/", {
              |      method: "POST",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin("|"),
            why = "Primitive request body and nullable boolean response should be encoded/decoded properly"
        )
    }
}