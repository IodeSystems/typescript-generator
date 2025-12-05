package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Ignore
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
}

class QueryParamsTest {

    @Test
    @Ignore
    fun testQueryParamsEmission() {
        val em = emitter<QueryParamsController>()
        val content = em.ts().content()
        content.assertContains(
            fragment = """
                list(query: { name?: string, minAge?: number, ids: number[], users: { name: string, age: number }[] }): Promise<string> {
                  return fetchInternal(this.opts, flattenQueryParams("/q", query, null), {
                    method: "GET"
                  }).then(r=>r.json())
                }
            """.trimIndent(),
            why = "Query method should gather params into a single query object and call flattenQueryParams"
        )

    }
}
