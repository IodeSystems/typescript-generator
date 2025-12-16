package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.Asserts.assertNotContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/**
 * Tests for complex sealed interface hierarchies where implementations
 * extend classes from another sealed interface hierarchy.
 *
 * This reproduces an issue where ClassGraph finds ALL implementations of an interface
 * (including indirect ones through inheritance), instead of only the permitted subclasses.
 */
class NestedSealedInterfaceInheritanceTest {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    sealed interface Ref {
        val orgId: Long
        val buId: Long?
        val locId: Long?

        open class Org(
            override val orgId: Long
        ) : Ref {
            override val buId: Long? = null
            override val locId: Long? = null
        }

        open class Bu(
            override val orgId: Long,
            override val buId: Long
        ) : Org(orgId) {
            override val locId: Long? = null
        }

        open class Loc(
            override val orgId: Long,
            override val buId: Long,
            override val locId: Long
        ) : Bu(orgId, buId)
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
    sealed interface SlugRef {
        val orgSlug: String
        val buSlug: String?
        val locSlug: String?

        open class Org(
            override val orgId: Long,
            override val orgSlug: String,
        ) : Ref.Org(orgId), SlugRef {
            override val buId: Long? = null
            override val buSlug: String? = null
            override val locId: Long? = null
            override val locSlug: String? = null
        }

        open class Bu(
            override val orgId: Long,
            override val orgSlug: String,
            override val buId: Long,
            override val buSlug: String,
        ) : Ref.Bu(orgId, buId), SlugRef {
            override val locId: Long? = null
            override val locSlug: String? = null
        }

        data class Loc(
            override val orgId: Long,
            override val orgSlug: String,
            override val buId: Long,
            override val buSlug: String,
            override val locId: Long,
            override val locSlug: String,
        ) : Ref.Loc(orgId, buId, locId), SlugRef
    }

    @RestController
    @RequestMapping("/nested")
    class NestedController {
        @GetMapping("/ref")
        fun getRef(): Ref = error("test")

        @GetMapping("/slugRef")
        fun getSlugRef(): SlugRef = error("test")
    }

    @Test
    fun refUnion_shouldOnlyContainDirectPermittedSubclasses() {
        val em = emitter(NestedController::class)
        val ts = em.ts().content()

        // Ref union should only contain Ref.Org, Ref.Bu, Ref.Loc
        // NOT SlugRef.Org, SlugRef.Bu, SlugRef.Loc (which indirectly implement Ref)

        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestRefOrg",
            why = "Ref.Org should be in Ref union"
        )
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestRefBu",
            why = "Ref.Bu should be in Ref union"
        )
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestRefLoc",
            why = "Ref.Loc should be in Ref union"
        )

        // The critical assertion: SlugRef implementations should NOT appear in RefUnion
        ts.assertNotContains(
            fragment = "NestedSealedInterfaceInheritanceTestSlugRefOrg | NestedSealedInterfaceInheritanceTestSlugRefBu",
            why = "SlugRef.Org and SlugRef.Bu should NOT be in Ref union - they are indirect implementations"
        )
    }

    @Test
    fun slugRefUnion_shouldOnlyContainDirectPermittedSubclasses() {
        val em = emitter(NestedController::class)
        val ts = em.ts().content()

        // SlugRef union should contain SlugRef.Org, SlugRef.Bu, SlugRef.Loc
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestSlugRefOrg",
            why = "SlugRef.Org should be in SlugRef union"
        )
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestSlugRefBu",
            why = "SlugRef.Bu should be in SlugRef union"
        )
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestSlugRefLoc",
            why = "SlugRef.Loc should be in SlugRef union"
        )
    }

    @Test
    fun slugRefOrg_shouldIntersectWithSlugRef() {
        val em = emitter(NestedController::class)
        val ts = em.ts().content()
        // SlugRef.Org should intersect with SlugRef (its parent sealed interface)
        // Note: it extends Ref.Org but the primary intersection should be with SlugRef
        ts.assertContains(
            fragment = "NestedSealedInterfaceInheritanceTestSlugRefOrg = NestedSealedInterfaceInheritanceTestSlugRef &",
            why = "SlugRef.Org should intersect with SlugRef interface"
        )

        // SlugRef.Org should include orgId field (from Ref.Org parent class)
        ts.assertContains(
            fragment = "orgId: number",
            why = "SlugRef.Org should have orgId field from parent Ref.Org"
        )
    }
}
