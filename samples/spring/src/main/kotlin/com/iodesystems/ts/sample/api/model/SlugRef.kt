package com.iodesystems.web.api.models

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
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
        fun bu(buId: Long, buSlug: String): Bu {
            return Bu(orgId, orgSlug, buId, buSlug)
        }
    }

    @JsonTypeName("Bu")
    open class Bu(
        override val orgId: Long,
        override val orgSlug: String,
        override val buId: Long,
        override val buSlug: String,
    ) : Ref.Bu(orgId, buId), SlugRef {
        override val locId: Long? = null
        override val locSlug: String? = null
        fun org() = Org(orgId, orgSlug)
        fun loc(locId: Long, locSlug: String) = Loc(orgId, orgSlug, buId, buSlug, locId, locSlug)
    }

    @JsonTypeName("Loc")
    data class Loc(
        override val orgId: Long,
        override val orgSlug: String,
        override val buId: Long,
        override val buSlug: String,
        override val locId: Long,
        override val locSlug: String,
    ) : Ref.Loc(orgId, buId, locId), SlugRef {
        fun org(): Org {
            return Org(orgId, orgSlug)
        }

        fun bu(): Bu {
            return Bu(
                orgId, orgSlug, buId, buSlug
            )
        }
    }
}
