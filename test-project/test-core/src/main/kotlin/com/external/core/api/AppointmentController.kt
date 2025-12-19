package com.external.core.api

import com.external.dep.annotations.ApiController
import com.external.dep.models.ApiResult
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/**
 * API controller using the @ApiController meta-annotation.
 * This tests that the TypeScript generator properly resolves @AliasFor
 * when the annotation comes from an external jar.
 */
@ApiController("/api/appointments")
class AppointmentController {

    data class CreateRequest(
        val date: String,
        val description: String
    )

    data class CancelRequest(
        val id: Long,
        val reason: String?
    )

    @PostMapping
    fun create(@RequestBody req: CreateRequest): ApiResult<String> = error("stub")

    @PostMapping("/cancel")
    fun cancel(@RequestBody req: CancelRequest): ApiResult<Unit> = error("stub")
}
