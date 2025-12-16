package com.iodesystems.ts.sample.model

import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Base reference type with organization hierarchy IDs.
 * This demonstrates a sealed interface with nested inheritance where
 * subtypes extend each other (Bu extends Org, Loc extends Bu).
 */
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

/**
 * Extended reference type that adds human-readable slugs.
 * This demonstrates a second sealed interface whose subtypes also extend
 * from the first sealed interface's subtypes.
 *
 * Key test scenario: SlugRef.Org implements SlugRef BUT ALSO extends Ref.Org.
 * The TypeScript generator should:
 * - Include SlugRef.Org, SlugRef.Bu, SlugRef.Loc in SlugRefUnion
 * - NOT include SlugRef.* types in RefUnion (they belong to SlugRef's hierarchy)
 */
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
