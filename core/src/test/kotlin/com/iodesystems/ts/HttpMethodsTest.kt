package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
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
        val out = TypeScriptGenerator.build {
            it.includeApi<HttpMethods>()
        }.generate()

        out.extraction.apis.let { apis ->
            assertEquals(1, apis.size)
        }

        val expected = """
                export class HttpMethods {
                  constructor(private opts: ApiOptions = {}) {}
                  get(): Promise<string> {
                    return fetchInternal(this.opts, "/verbs", {
                      method: "GET"
                    }).then(r=>r.json())
                  }
                  post(req: string): Promise<string> {
                    return fetchInternal(this.opts, "/verbs", {
                      method: "POST",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                  put(req: string): Promise<string> {
                    return fetchInternal(this.opts, "/verbs", {
                      method: "PUT",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                  patch(req: string): Promise<string> {
                    return fetchInternal(this.opts, "/verbs", {
                      method: "PATCH",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                  delete(): Promise<string> {
                    return fetchInternal(this.opts, "/verbs", {
                      method: "DELETE"
                    }).then(r=>r.json())
                  }
                }
            """.trimIndent()

        out.tsApis().let { ts ->
            assertEquals(expected, ts)
        }
    }
}
