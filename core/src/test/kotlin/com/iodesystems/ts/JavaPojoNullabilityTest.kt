package com.iodesystems.ts

import com.iodesystems.ts.JavaParam
import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals

@RestController
@RequestMapping("/java-pojo")
class JavaPojoApi {
    @RequestMapping
    fun get(): JavaParam = JavaParam()
}

class JavaPojoNullabilityTest {
    @Test
    fun javaPojoFieldNullabilityRespectedWithoutClassLoading() {
        val out = TypeScriptGenerator.build {
            it
                .includeApi { name -> name.endsWith("JavaPojoApi") }
                // Register custom optional annotation to mark certain fields as optional
                .optionalAnnotations("com.iodesystems.ts.TsOptional")
        }.generate()

        val expected = """
                export class JavaPojoApi {
                  constructor(private opts: ApiOptions = {}) {}
                  get(): Promise<JavaParam> {
                    return fetchInternal(this.opts, "/java-pojo", {
                      method: "GET"
                    }).then(r=>r.json())
                  }
                }

                /**
                 * JVM: com.iodesystems.ts.JavaParam
                 * Referenced by:
                 * - com.iodesystems.ts.JavaPojoApi.get
                 */
                type JavaParam = {
                  name: string | null
                  nick?: string
                  count: number
                }
            """.trimIndent()

        assertEquals(expected, out.tsApis())
    }
}
