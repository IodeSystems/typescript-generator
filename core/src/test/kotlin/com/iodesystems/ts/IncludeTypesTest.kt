package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

@RestController
@RequestMapping("/api/types")
class IncludeTypesApi {
    data class UsedType(
        val name: String
    )

    @GetMapping("/get")
    fun get(): UsedType = error("not used in tests")
}

// Types that are not referenced by the API but should be included explicitly
data class UnreferencedType(
    val id: Long,
    val description: String
)

data class AnotherUnreferencedType(
    val value: Int,
    val nested: UnreferencedType
)

enum class UnreferencedEnum {
    OPTION_A, OPTION_B, OPTION_C
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
sealed interface UnreferencedUnion {
    data class VariantA(val valueA: String) : UnreferencedUnion
    data class VariantB(val valueB: Int) : UnreferencedUnion
}

class IncludeTypesTest {
    @Test
    fun includesTypesExplicitlyByFqcn() {
        val em = emitter(IncludeTypesApi::class) {
            outputDirectory("./tmp")
            include(
                "com.iodesystems.ts.UnreferencedType",
                "com.iodesystems.ts.UnreferencedEnum"
            )
        }
        val out = em.ts().content()

        // Verify the API method is present
        out.assertContains(
            fragment = "get(): AbortablePromise<IncludeTypesApiUsedType>",
            why = "API method should be present"
        )

        // Verify UsedType is emitted (used by API)
        out.assertContains(
            fragment = """
                export type IncludeTypesApiUsedType = {
                  name: string
                }
            """.trimIndent(),
            why = "UsedType should be emitted because it's used by the API"
        )

        // Verify UnreferencedType is emitted (explicitly included)
        out.assertContains(
            fragment = "export type UnreferencedType = {",
            why = "UnreferencedType should be emitted because it was explicitly included"
        )
        out.assertContains(
            fragment = "description: string",
            why = "UnreferencedType should have description field"
        )
        out.assertContains(
            fragment = "id: number",
            why = "UnreferencedType should have id field"
        )

        // Verify UnreferencedEnum is emitted (explicitly included)
        out.assertContains(
            fragment = "export type UnreferencedEnum = 'OPTION_A' | 'OPTION_B' | 'OPTION_C'",
            why = "UnreferencedEnum should be emitted because it was explicitly included"
        )

        // Verify AnotherUnreferencedType is NOT emitted (not explicitly included)
        val hasAnotherUnreferenced = out.contains("AnotherUnreferencedType")
        assert(!hasAnotherUnreferenced) {
            "AnotherUnreferencedType should not be emitted because it was not explicitly included"
        }
    }

    @Test
    fun includesTypesExplicitlyByKClass() {
        val em = emitter(IncludeTypesApi::class) {
            outputDirectory("./tmp")
            include(UnreferencedType::class, UnreferencedEnum::class)
        }
        val out = em.ts().content()

        // Verify UnreferencedType is emitted
        out.assertContains(
            fragment = "export type UnreferencedType = {",
            why = "UnreferencedType should be emitted when included via KClass"
        )
        out.assertContains(
            fragment = "description: string",
            why = "UnreferencedType should have description field"
        )
        out.assertContains(
            fragment = "id: number",
            why = "UnreferencedType should have id field"
        )

        // Verify UnreferencedEnum is emitted
        out.assertContains(
            fragment = "export type UnreferencedEnum = 'OPTION_A' | 'OPTION_B' | 'OPTION_C'",
            why = "UnreferencedEnum should be emitted when included via KClass"
        )
    }

    @Test
    fun includesTypesWithReifiedGeneric() {
        val em = emitter(IncludeTypesApi::class) {
            outputDirectory("./tmp")
            include(UnreferencedType::class, UnreferencedEnum::class)
        }
        val out = em.ts().content()

        // Verify UnreferencedType is emitted
        out.assertContains(
            fragment = "export type UnreferencedType",
            why = "UnreferencedType should be emitted when included via KClass"
        )

        // Verify UnreferencedEnum is emitted
        out.assertContains(
            fragment = "export type UnreferencedEnum",
            why = "UnreferencedEnum should be emitted when included via KClass"
        )
    }

    @Test
    fun includesNestedTypesRecursively() {
        val em = emitter(IncludeTypesApi::class) {
            outputDirectory("./tmp")
            include(AnotherUnreferencedType::class)
        }
        val out = em.ts().content()

        // Verify AnotherUnreferencedType is emitted
        out.assertContains(
            fragment = "export type AnotherUnreferencedType = {",
            why = "AnotherUnreferencedType should be emitted when explicitly included"
        )
        out.assertContains(
            fragment = "value: number",
            why = "AnotherUnreferencedType should have value field"
        )
        out.assertContains(
            fragment = "nested: UnreferencedType",
            why = "AnotherUnreferencedType should have nested field"
        )

        // Verify nested UnreferencedType is also emitted
        out.assertContains(
            fragment = "export type UnreferencedType = {",
            why = "UnreferencedType should be emitted because it's referenced by AnotherUnreferencedType"
        )
        out.assertContains(
            fragment = "description: string",
            why = "UnreferencedType should have description field"
        )
        out.assertContains(
            fragment = "id: number",
            why = "UnreferencedType should have id field"
        )
    }

    @Test
    fun includesUnionTypesWithDiscriminator() {
        val em = emitter(IncludeTypesApi::class) {
            outputDirectory("./tmp")
            include(UnreferencedUnion::class)
        }
        val out = em.ts().content()

        // Verify base interface is emitted
        out.assertContains(
            fragment = "export type UnreferencedUnion = {",
            why = "Union base type should be emitted"
        )

        // Verify VariantA is emitted with discriminator
        out.assertContains(
            fragment = "export type UnreferencedUnionVariantA = UnreferencedUnion & {",
            why = "VariantA should be emitted extending the base"
        )
        out.assertContains(
            fragment = "\"@type\": \"VariantA\"",
            why = "VariantA should have discriminator"
        )
        out.assertContains(
            fragment = "valueA: string",
            why = "VariantA should have its field"
        )

        // Verify VariantB is emitted with discriminator
        out.assertContains(
            fragment = "export type UnreferencedUnionVariantB = UnreferencedUnion & {",
            why = "VariantB should be emitted extending the base"
        )
        out.assertContains(
            fragment = "\"@type\": \"VariantB\"",
            why = "VariantB should have discriminator"
        )
        out.assertContains(
            fragment = "valueB: number",
            why = "VariantB should have its field"
        )

        // Verify the union type is emitted
        out.assertContains(
            fragment = "export type UnreferencedUnionUnion = UnreferencedUnion & (UnreferencedUnionVariantA | UnreferencedUnionVariantB)",
            why = "Union type combining all variants should be emitted"
        )
    }
}
