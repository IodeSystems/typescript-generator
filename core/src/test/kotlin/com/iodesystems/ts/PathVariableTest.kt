package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Ignore
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
    @Ignore
    fun testPathVariableEmission() {
        val em = emitter<PathVarController>()
        val content = em.ts().content()
        content.assertContains(
            fragment = """
                get(path: { id: number }): Promise<string> {
                  return fetchInternal(this.opts, "/p/user/{id}".replace("{id}", String(path.id)), {
                    method: "GET"
                  }).then(r=>r.json())
                }
            """.trimIndent(),
            why = "Path variable should be replaced in URL for single id"
        )
        content.assertContains(
            fragment = """
                both(path: { userId: number, postId: number }, query: { q: string }): Promise<string> {
                  return fetchInternal(this.opts, flattenQueryParams("/p/user/{id}/posts/{postId}".replace("{id}", String(path.userId)).replace("{postId}", String(path.postId)), query, null), {
                    method: "GET"
                  }).then(r=>r.json())
                }
            """.trimIndent(),
            why = "Multiple path variables should be replaced and combined with query params"
        )
    }
}
