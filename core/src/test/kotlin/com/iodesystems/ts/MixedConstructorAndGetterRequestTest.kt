package com.iodesystems.ts

import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

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
        val em = emitter(MixedApi::class)
        val c = em.ts().content()
        c.assertContains(
            """
            export type MixedApiRequest = {
              param: string
              wrap: MixedWrapper
              getter: number
              field: boolean
            }
        """.trimIndent(),
            why = "Generated types should include ctor values, getters, and fields!"
        )
    }
}
