package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.*
import kotlin.test.Test

data class QUser(val name: String, val age: Int)

@RestController
@RequestMapping("/q")
class QueryParamsController {

    @GetMapping
    fun list(
        @RequestParam("name") name: String?,
        @RequestParam(required = false) minAge: Int?,
        @RequestParam ids: List<Long>,
        @RequestParam users: List<QUser>,
    ): String = "ok"


    @GetMapping("/with/{path}/{param}")
    fun withPath(
        @PathVariable("path") path: String,
        @PathVariable("param") param: Long,
        @RequestParam("name") name: String?,
        @RequestParam(required = false) minAge: Int?,
        @RequestParam ids: List<Long>,
        @RequestParam users: List<QUser>,
    ): String = "ok"
}

class QueryParamsTest {

    @Test
    fun testQueryParamsEmission() {
        val em = emitter(QueryParamsController::class)
        val content = em.ts().content()
        content.assertContains(
            """
            export type QueryParamsControllerListQuery = {
              ids: Array<number>
              minAge?: number | null | undefined
              name?: string | null | undefined
              users: Array<QUser>
            }
        """.trimIndent(), "The query type should be emitted"
        )
        content.assertContains(
            fragment = """
               |  list(query: QueryParamsControllerListQuery): AbortablePromise<string> {
               |    return fetchInternal(this.opts, flattenQueryParams("/q", query, null), {
               |      method: "GET"
               |    }).then(r=>r.json())
               |  }
            """.trimMargin("|"),
            why = "Query method should gather params into a single query object and call flattenQueryParams"
        )

        content.assertContains("""
            |  withPath(path: { path: string, param: string | number }, query: QueryParamsControllerWithPathQuery): AbortablePromise<string> {
            |    return fetchInternal(this.opts, flattenQueryParams("/q/with/{path}/{param}".replace("{path}", String(path.path)).replace("{param}", String(path.param)), query, null), {
            |      method: "GET"
            |    }).then(r=>r.json())
            |  }
        """.trimMargin(), "should work well with path replacements as well")

    }
}
