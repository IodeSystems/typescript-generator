package com.iodesystems.ts.sample.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/sample")
class SampleApi {
    data class Add(val a: Int, val b: Int) {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
        sealed interface Response {
            data class Success(
                val result: Int,
                val at: OffsetDateTime? = null
            ) : Response

            data class Failure(val error: String) : Response
        }
    }

    @PostMapping
    fun add(@RequestBody user: Add): Add.Response {
        return Add.Response.Failure("Test implementation!")
    }
}