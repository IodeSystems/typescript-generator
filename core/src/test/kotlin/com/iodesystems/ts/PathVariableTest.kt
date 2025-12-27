package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.*
import kotlin.test.Test

@RestController
@RequestMapping("/p")
class PathVarController {

    @GetMapping("/user/{id}")
    fun get(@PathVariable id: String): String = "ok"

    @GetMapping("/user/{id}/posts/{postId}")
    fun both(
        @PathVariable("id") userId: Long,
        @PathVariable postId: Long,
        @RequestParam q: String? = null,
    ): String = "ok"
}

class PathVariableTest {

    @Test
    fun testPathVariableEmission() {
        val em = emitter(PathVarController::class)
        val content = em.ts().content()
        content.assertContains(
            fragment = """
              |  get(path: { id: string }): AbortablePromise<string> {
              |    return fetchInternal(this.opts, "/p/user/{id}".replace("{id}", String(path.id)), {
              |      method: "GET"
              |    }).then(r=>r.json())
              |  }
            """.trimMargin("|"),
            why = "Path variable should be replaced in URL for single id"
        )
        content.assertContains(
            fragment = """
              |  both(path: { userId: string | number, postId: string | number }, query: PathVarControllerBothQuery): AbortablePromise<string> {
              |    return fetchInternal(this.opts, flattenQueryParams("/p/user/{id}/posts/{postId}".replace("{id}", String(path.userId)).replace("{postId}", String(path.postId)), query, null), {
              |      method: "GET"
              |    }).then(r=>r.json())
              |  }
            """.trimMargin("|"),
            why = "Multiple path variables should be replaced and combined with query params"
        )
    }
}
