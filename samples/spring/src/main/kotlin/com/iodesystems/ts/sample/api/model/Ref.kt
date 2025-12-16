package com.iodesystems.web.api.models

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName


@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, property = "_type")
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
