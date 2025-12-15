package com.external.dep.models

import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * A user model that lives in a completely different package from the API.
 * This tests that the TypeScript generator can extract types from external modules.
 */
data class User(
    val id: Long,
    val name: String,
    val email: String?,
    val role: UserRole,
    val preferences: UserPreferences?
)

enum class UserRole {
    ADMIN,
    USER,
    GUEST
}

data class UserPreferences(
    val theme: String = "light",
    val notifications: Boolean = true
)

/**
 * A polymorphic response type to test union extraction from external packages.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
sealed interface ApiResult<T> {
    val requestId: String

    data class Success<T>(
        override val requestId: String,
        val data: T
    ) : ApiResult<T>

    data class Failure<T>(
        override val requestId: String,
        val error: String,
        val code: Int
    ) : ApiResult<T>
}

/**
 * A generic wrapper to test generic type extraction from external packages.
 */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Long
)
