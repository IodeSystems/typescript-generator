package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.*
import kotlin.test.Test
import kotlin.test.assertEquals

@RestController
@RequestMapping("/verbs")
class HttpMethods {

    @GetMapping
    fun get(): String = "ok"

    @PostMapping
    fun post(@RequestBody req: String): String = req

    @PutMapping
    fun put(@RequestBody req: String): String = req

    @PatchMapping
    fun patch(@RequestBody req: String): String = req

    @DeleteMapping
    fun delete(): String = "deleted"
}

class HttpMethodsTest {

    @Test
    fun testHttpMethods() {
        val em = emitter(HttpMethods::class)
        val content = em.ts().content()
        // Only check each API method block (not every line independently)
        content.assertContains(
            fragment = """
              |  get(): Promise<string> {
              |    return fetchInternal(this.opts, "/verbs", {
              |      method: "GET"
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "missing GET block"
        )

        content.assertContains(
            fragment = """
              |  post(req: string): Promise<string> {
              |    return fetchInternal(this.opts, "/verbs", {
              |      method: "POST",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "missing POST block"
        )

        content.assertContains(
            fragment = """
              |  put(req: string): Promise<string> {
              |    return fetchInternal(this.opts, "/verbs", {
              |      method: "PUT",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "missing PUT block"
        )

        content.assertContains(
            fragment = """
              |  patch(req: string): Promise<string> {
              |    return fetchInternal(this.opts, "/verbs", {
              |      method: "PATCH",
              |      headers: {'Content-Type': 'application/json'},
              |      body: JSON.stringify(req)
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "missing PATCH block"
        )

        content.assertContains(
            fragment = """
              |  delete(): Promise<string> {
              |    return fetchInternal(this.opts, "/verbs", {
              |      method: "DELETE"
              |    }).then(r=>r.json())
              |  }
            """.trimMargin(),
            why = "missing DELETE block"
        )

    }
}
