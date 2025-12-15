package com.external.unions

import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class ObjectUnions {
    data class Create(val id: String) {
        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, include = JsonTypeInfo.As.PROPERTY, property = "_type")
        sealed interface Response {
            data object Ok : Response
            data object UserNotFound : Response
            data object ChallengeNotFound : Response
            data object AuthFailed : Response
        }
    }

    @PostMapping
    fun create(): Create.Response = Create.Response.Ok
}