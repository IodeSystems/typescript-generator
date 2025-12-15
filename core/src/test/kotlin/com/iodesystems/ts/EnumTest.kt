package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.junit.Ignore
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/api/enums")
class EnumApi {
    enum class Color {
        RED, GREEN, BLUE
    }

    data class Payload(
        val color: Color,
        val colors: List<Color>
    )

    @GetMapping("/get")
    fun get(): Payload = error("not used in tests")
}

class EnumTest {
    @Test
    fun emitsEnumsAsStringUnionLiterals() {
        val em = emitter(EnumApi::class) { outputDirectory("./tmp") }
        val out = em.ts().content()

        // The object type should reference the simple enum name
        out.assertContains(
            fragment =
            """
                export type EnumApiPayload = {
                  color: EnumApiColor
                  colors: Array<EnumApiColor>
                }
            """.trimIndent(),
            why = "Payload should reference the simple enum name"
        )

        // And there should be a top-level exported enum alias with the union literal
        out.assertContains(
            fragment =
            """
                export type EnumApiColor = 'RED' | 'GREEN' | 'BLUE'
            """.trimIndent(),
            why = "Enum should be exported as a grouped alias and re-exported as simple name"
        )

        // The method should return the payload type (loose matching to avoid indentation issues)
        out.assertContains(
            fragment = "get(): Promise<EnumApiPayload>",
            why = "Method signature should be present"
        )
        out.assertContains(
            fragment = "return fetchInternal(this.opts, \"/api/enums/get\"",
            why = "GET path should be correct"
        )
    }
}
