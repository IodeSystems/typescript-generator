package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals

@RestController
@RequestMapping("/p")
class PathVarController {

    @GetMapping("/user/{id}")
    fun get(@PathVariable id: Long): String = "ok"

    @GetMapping("/user/{id}/posts/{postId}")
    fun both(
        @PathVariable("id") userId: Long,
        @PathVariable postId: Long,
        @RequestParam q: String,
    ): String = "ok"
}

class PathVariableTest {

    @Test
    fun testPathVariableEmission() {
        val out = TypeScriptGenerator.build { it ->
            it.includeApi { it.endsWith("PathVarController") }
        }.generate()

        val expected = """
                export class PathVarController {
                  constructor(private opts: ApiOptions = {}) {}
                  get(path: { id: number }): Promise<string> {
                    return fetchInternal(this.opts, "/p/user/{id}".replace("{id}", String(path.id)), {
                      method: "GET"
                    }).then(r=>r.json())
                  }
                  both(path: { userId: number, postId: number }, query: { q: string }): Promise<string> {
                    return fetchInternal(this.opts, flattenQueryParams("/p/user/{id}/posts/{postId}".replace("{id}", String(path.userId)).replace("{postId}", String(path.postId)), query, null), {
                      method: "GET"
                    }).then(r=>r.json())
                  }
                }
            """.trimIndent()

        out.tsApis().let { ts ->
            assertEquals(expected, ts)
        }
    }
}
