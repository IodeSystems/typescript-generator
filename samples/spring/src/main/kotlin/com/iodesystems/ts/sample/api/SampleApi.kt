package com.iodesystems.ts.sample.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.sample.model.Ref
import com.iodesystems.ts.sample.model.SlugRef
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime

@RestController
@RequestMapping("/api/sample")
@CrossOrigin // Allow dev UI on a different port to call these endpoints
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

    data class Ping(val message: String) {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
        sealed interface Response {
            val at: OffsetDateTime?

            data class Pong(
                val message: String,
                override val at: OffsetDateTime? = null
            ) : Response
        }
    }

    @PostMapping("/ping")
    fun ping(@RequestBody ping: Ping): Ping.Response {
        return Ping.Response.Pong(message = "pong")
    }

    // Endpoints that use the nested sealed interfaces from model package
    // These types are OUTSIDE the packageAccept filter but should still be
    // correctly processed when referenced from API methods

    @GetMapping("/ref")
    fun getRef(): Ref {
        return Ref.Org(orgId = 1)
    }

    @GetMapping("/slug-ref")
    fun getSlugRef(): SlugRef {
        return SlugRef.Org(orgId = 1, orgSlug = "acme")
    }
}