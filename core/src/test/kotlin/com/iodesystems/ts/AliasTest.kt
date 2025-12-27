package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/api/alias")
class AliasTestApi {
    data class ByteArray(val data: String)

    data class Request(
        val bytes: ByteArray,
        val name: String
    )

    @PostMapping("/test")
    fun test(@RequestBody req: Request): ByteArray = error("not used in tests")
}

class AliasTest {
    @Test
    fun aliasReplacesTypeNameWithCustomName() {
        val em = emitter(AliasTestApi::class) {
            outputDirectory("./tmp")
            alias($$"com.iodesystems.ts.AliasTestApi$ByteArray", "Bytes")
        }
        val out = em.ts().content()

        // Verify type is emitted with aliased name
        out.assertContains(
            fragment = "export type Bytes = {",
            why = "ByteArray should be renamed to Bytes via alias"
        )

        // Verify the field uses the aliased name
        out.assertContains(
            fragment = "bytes: Bytes",
            why = "Request should reference Bytes, not ByteArray"
        )

        // Verify return type uses the aliased name
        out.assertContains(
            fragment = "test(req: AliasTestApiRequest): AbortablePromise<Bytes>",
            why = "Method should return Bytes, not ByteArray"
        )
    }

    @Test
    fun aliasBypassesTypeNameReplacements() {
        val em = emitter(AliasTestApi::class) {
            outputDirectory("./tmp")
            // This should NOT affect our alias
            addTypeNameReplacement("Array", "Arr")
            alias("com.iodesystems.ts.AliasTestApi\$ByteArray", "Bytes")
        }
        val out = em.ts().content()

        // Verify alias takes precedence over replacement
        out.assertContains(
            fragment = "export type Bytes = {",
            why = "Alias should bypass typeNameReplacements"
        )

        // Should NOT contain "export type ByteArr" (replacement should be bypassed by alias)
        val hasByteArrType = out.contains("export type ByteArr")
        assert(!hasByteArrType) {
            "Should not have ByteArr as a type name - alias should bypass replacements. Output:\n$out"
        }

        // Should NOT contain "AliasTestApiByteArr" in field references
        val hasByteArrField = out.contains(": ByteArr")
        assert(!hasByteArrField) {
            "Should not have ByteArr in field types - alias should bypass replacements. Output:\n$out"
        }
    }

    @Test
    fun aliasWithMappedTypeCreatesTypeAlias() {
        val em = emitter(AliasTestApi::class) {
            outputDirectory("./tmp")
            alias("com.iodesystems.ts.AliasTestApi\$ByteArray", "Bytes")
            mappedTypes(mapOf("com.iodesystems.ts.AliasTestApi\$ByteArray" to "string"))
        }
        val out = em.ts().content()

        // Verify type alias is created: export type Bytes = string
        out.assertContains(
            fragment = "export type Bytes = string",
            why = "Should create type alias when both alias and mappedType are used"
        )

        // Verify the field uses the aliased name
        out.assertContains(
            fragment = "bytes: Bytes",
            why = "Request should reference Bytes"
        )

        // Verify return type uses the aliased name
        out.assertContains(
            fragment = "test(req: AliasTestApiRequest): AbortablePromise<Bytes>",
            why = "Method should return Bytes"
        )
    }

    @Test
    fun aliasWithKClass() {
        val em = emitter(AliasTestApi::class) {
            outputDirectory("./tmp")
            alias(AliasTestApi.ByteArray::class, "ByteData")
        }
        val out = em.ts().content()

        // Verify type is emitted with aliased name
        out.assertContains(
            fragment = "export type ByteData = {",
            why = "ByteArray should be renamed to ByteData via KClass alias"
        )

        // Verify the field uses the aliased name
        out.assertContains(
            fragment = "bytes: ByteData",
            why = "Request should reference ByteData"
        )
    }

    @Test
    fun addAliasAddsToExistingAliases() {
        val em = emitter(AliasTestApi::class) {
            outputDirectory("./tmp")
            alias("com.iodesystems.ts.AliasTestApi\$ByteArray", "FirstName")
            addAlias(mapOf("com.iodesystems.ts.AliasTestApi\$ByteArray" to "Bytes"))
        }
        val out = em.ts().content()

        // The second alias should override the first
        out.assertContains(
            fragment = "export type Bytes = {",
            why = "addAlias should override previous alias"
        )
    }
}
