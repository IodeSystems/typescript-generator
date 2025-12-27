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
        val isActive: Boolean,                    // Should become "active" in JSON
        val isOptional: Optional<Boolean>,        // Should become "optional" in JSON (non-boolean is-getter)
        val isNullable: Boolean?,                 // Should become "nullable" in JSON
        val isOptionalNullable: Optional<Boolean>?, // Should become "optionalNullable" in JSON
        val enabled: Boolean,                     // Control - no is-prefix, stays "enabled"
        val isURL: Boolean,                       // Should become "url" (lowercase) with default Jackson naming
    )

    @GetMapping
    fun getIsPrefixTest(): IsPrefixTest = error("stub")
}

class IsPrefixNamingTest {

    @Test
    fun `is-prefix boolean properties should have is stripped with default config`() {
        val em = emitter(IsPrefixApi::class) { outputDirectory("./tmp") }
        val content = em.ts().content()

        // With default config (autoDetectIsGetters=true, allowIsGettersForNonBoolean=true, useStdBeanNaming=false)
        // All is-prefix properties should have "is" stripped
        content.assertContains(
            fragment = """
                export type IsPrefixApiIsPrefixTest = {
                  active: boolean
                  enabled: boolean
                  nullable: boolean | null
                  optional: boolean | null
                  optionalNullable: boolean | null
                  url: boolean
                }
            """.trimIndent(),
            why = "is-prefix properties should have 'is' stripped and follow Jackson naming conventions"
        )
    }

    @Test
    fun `disabling autoDetectIsGetters should preserve is-prefix for boolean properties`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            autoDetectIsGetters(false)
        }
        val content = em.ts().content()

        // With autoDetectIsGetters=false, boolean is* getters should preserve "is" prefix
        // Non-boolean is* getters still follow allowIsGettersForNonBoolean (default true)
        content.assertContains(
            fragment = "isActive: boolean",
            why = "boolean is-prefix properties should preserve 'is' when autoDetectIsGetters=false"
        )
        content.assertContains(
            fragment = "isNullable: boolean | null",
            why = "nullable boolean is-prefix properties should preserve 'is' when autoDetectIsGetters=false"
        )
        content.assertContains(
            fragment = "isURL: boolean",
            why = "boolean is-prefix properties should preserve 'is' when autoDetectIsGetters=false"
        )
        // Non-boolean is* getters should still have is stripped (allowIsGettersForNonBoolean is still true)
        content.assertContains(
            fragment = "optional: boolean | null",
            why = "non-boolean is-prefix properties should have 'is' stripped when allowIsGettersForNonBoolean=true"
        )
    }

    @Test
    fun `disabling allowIsGettersForNonBoolean should preserve is-prefix for non-boolean properties`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            allowIsGettersForNonBoolean(false)
        }
        val content = em.ts().content()

        // With allowIsGettersForNonBoolean=false, non-boolean is* getters should preserve "is" prefix
        // Boolean is* getters still follow autoDetectIsGetters (default true)
        content.assertContains(
            fragment = "active: boolean",
            why = "boolean is-prefix properties should have 'is' stripped when autoDetectIsGetters=true"
        )
        content.assertContains(
            fragment = "isOptional: boolean | null",
            why = "non-boolean is-prefix properties should preserve 'is' when allowIsGettersForNonBoolean=false"
        )
        content.assertContains(
            fragment = "isOptionalNullable: boolean | null",
            why = "non-boolean is-prefix properties should preserve 'is' when allowIsGettersForNonBoolean=false"
        )
    }

    @Test
    fun `useStdBeanNaming should preserve uppercase after prefix removal`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            useStdBeanNaming(true)
        }
        val content = em.ts().content()

        // With useStdBeanNaming=true, "isURL" should become "URL" not "url"
        content.assertContains(
            fragment = "URL: boolean",
            why = "useStdBeanNaming=true should preserve uppercase: isURL -> URL"
        )
    }

    @Test
    fun `all naming options disabled should preserve original property names`() {
        val em = emitter(IsPrefixApi::class) {
            outputDirectory("./tmp")
            autoDetectIsGetters(false)
            allowIsGettersForNonBoolean(false)
        }
        val content = em.ts().content()

        // With both options disabled, all is-prefix properties should preserve their names
        content.assertContains(
            fragment = "isActive: boolean",
            why = "is-prefix should be preserved when autoDetectIsGetters=false"
        )
        content.assertContains(
            fragment = "isOptional: boolean | null",
            why = "is-prefix should be preserved when allowIsGettersForNonBoolean=false"
        )
        content.assertContains(
            fragment = "isURL: boolean",
            why = "is-prefix should be preserved when autoDetectIsGetters=false"
        )
    }
}
