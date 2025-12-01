package com.iodesystems.ts

import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertEquals

// Additional types to verify that only the current type's implemented interfaces are intersected
object MixedNested {
    interface SomeInterface {
        fun getInterfaceValue(): String = this::class.java.simpleName
    }
}

class MixedReferencedType(
    val value: Int?
) : MixedNested.SomeInterface

data class MixedWrapper(
    val ref: MixedReferencedType?
)

@RestController
@RequestMapping("/mixed")
class MixedApi {
    data class Request(
        val param: String,
        val wrap: MixedWrapper
    ) {
        fun getGetter() = 1
        val field: Boolean = false
    }

    @PostMapping
    fun post(@RequestBody req: Request): Request = req
}

class MixedConstructorAndGetterRequestTest {

    @Test
    fun mixedConstructorAndGetterAreRespectedInRequestBody() {
        val out = TypeScriptGenerator.build {
            it.includeApi { name -> name.endsWith("MixedApi") }
        }.generate()

        val expected = $$"""
                export class MixedApi {
                  constructor(private opts: ApiOptions = {}) {}
                  post(req: MixedApiRequest): Promise<MixedApiRequest> {
                    return fetchInternal(this.opts, "/mixed", {
                      method: "POST",
                      headers: {'Content-Type': 'application/json'},
                      body: JSON.stringify(req)
                    }).then(r=>r.json())
                  }
                }

                /**
                 * JVM: com.iodesystems.ts.MixedNested$SomeInterface
                 * Referenced by:
                 * - com.iodesystems.ts.MixedReferencedType
                 */
                type MixedNestedSomeInterface = {
                  interfaceValue: string
                }

                /**
                 * JVM: com.iodesystems.ts.MixedReferencedType
                 * Referenced by:
                 * - com.iodesystems.ts.MixedApi.post
                 */
                type MixedReferencedType = {
                  value: number | null
                } & MixedNestedSomeInterface

                /**
                 * JVM: com.iodesystems.ts.MixedWrapper
                 * Referenced by:
                 * - com.iodesystems.ts.MixedApi.post
                 */
                type MixedWrapper = {
                  ref: MixedReferencedType | null
                }

                /**
                 * JVM: com.iodesystems.ts.MixedApi$Request
                 * Referenced by:
                 * - com.iodesystems.ts.MixedApi.post
                 */
                type MixedApiRequest = {
                  param: string
                  wrap: MixedWrapper
                  field: boolean
                  getter: number
                }
            """.trimIndent()

        val actual = out.tsApis()
        assertEquals(expected, actual)
    }
}
