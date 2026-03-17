package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
import kotlin.test.Test

@RestController
@RequestMapping("/api/is-prefix")
class IsPrefixApi {
    /**
     * Test data class with various is-prefix property scenarios.
     * Mirrors the test case from samples/spring to verify Jackson naming behavior.
     */
    data class IsPrefixTest(
        val isActive: Boolean,                    // Jackson+KotlinModule: "isActive"
        val isOptional: Optional<Boolean>,        // Jackson+KotlinModule: "isOptional"
        val isNullable: Boolean?,                 // Jackson+KotlinModule: "isNullable"
        val isOptionalNullable: Optional<Boolean>?, // Jackson+KotlinModule: "isOptionalNullable"
        val enabled: Boolean,                     // Control - no is-prefix, stays "enabled"
        val isURL: Boolean,                       // Jackson+KotlinModule: "isURL"
    )

    @GetMapping
    fun getIsPrefixTest(): IsPrefixTest = error("stub")
}

class IsPrefixNamingTest {

    @Test
    fun `Kotlin data class is-prefix properties should use constructor param names matching KotlinModule`() {
        val em = emitter(IsPrefixApi::class) { outputDirectory("./tmp") }
        val content = em.ts().content()

        // Kotlin data classes: Jackson's KotlinModule uses constructor parameter names directly,
        // so "isActive" stays "isActive" (not stripped to "active")
        content.assertContains(
            fragment = """
                export type IsPrefixApiIsPrefixTest = {
                  enabled: boolean
                  isActive: boolean
                  isNullable: boolean | null
                  isOptional: boolean | null
                  isOptionalNullable: boolean | null
                  isURL: boolean
                }
            """.trimIndent(),
            why = "Kotlin data class properties should use constructor parameter names (matching Jackson+KotlinModule)"
        )
    }

    @Test
    fun `Kotlin data class naming is not affected by autoDetectIsGetters config`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            autoDetectIsGetters(false)
        }
        val content = em.ts().content()

        // Kotlin data classes always use constructor parameter names (matching KotlinModule),
        // regardless of autoDetectIsGetters setting
        content.assertContains(
            fragment = "isActive: boolean",
            why = "Kotlin data class property names are unaffected by autoDetectIsGetters"
        )
        content.assertContains(
            fragment = "isOptional: boolean | null",
            why = "Kotlin data class property names are unaffected by autoDetectIsGetters"
        )
    }

    @Test
    fun `Kotlin data class naming is not affected by useStdBeanNaming config`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            useStdBeanNaming(true)
        }
        val content = em.ts().content()

        // Kotlin data classes use constructor parameter names, not JavaBean conventions
        content.assertContains(
            fragment = "isURL: boolean",
            why = "Kotlin data class uses constructor param name 'isURL', not JavaBean-derived"
        )
    }
}
