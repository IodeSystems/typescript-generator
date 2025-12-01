package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun testQueryParamsEmission() {
        val out = TypeScriptGenerator.build { it ->
            it.includeApi { it.endsWith("QueryParamsController") }
        }.generate()

        out.extraction.apis.let { apis ->
            assertEquals(1, apis.size)
        }

        val expected = """
                export class QueryParamsController {
                  constructor(private opts: ApiOptions = {}) {}
                  list(query: { name?: string, minAge?: number, ids: number[], users: { name: string, age: number }[] }): Promise<string> {
                    return fetchInternal(this.opts, flattenQueryParams("/q", query, null), {
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
