package com.external.core.api

import com.external.dep.models.ApiResult
import com.external.dep.models.Page
import com.external.dep.models.User
import com.external.dep.models.UserPreferences
import org.springframework.web.bind.annotation.*

/**
 * API controller that uses types from a completely different package (com.external.dep.models).
 * This tests the "doomsday scenario" where ClassGraph couldn't properly extract types
 * from external modules that weren't in the API's package hierarchy.
 */
@RestController
@RequestMapping("/api/users")
class UserController {

    /**
     * Returns a user by ID - tests simple external type usage
     */
    @GetMapping("/{id}")
    fun getUser(@PathVariable id: Long): ApiResult<User> = error("test")

    /**
     * Lists users with pagination - tests generic external types
     */
    @GetMapping
    fun listUsers(
        @RequestParam page: Int,
        @RequestParam pageSize: Int
    ): Page<User> = error("test")

    /**
     * Creates a new user - tests external type in request body
     */
    @PostMapping
    fun createUser(@RequestBody user: User): ApiResult<User> = error("test")

    /**
     * Updates user preferences - tests nested external types
     */
    @PutMapping("/{id}/preferences")
    fun updatePreferences(
        @PathVariable id: Long,
        @RequestBody preferences: UserPreferences
    ): ApiResult<UserPreferences> = error("test")

    /**
     * Deletes a user - tests void return with external request
     */
    @DeleteMapping("/{id}")
    fun deleteUser(@PathVariable id: Long): ApiResult<Unit> = error("test")
}
