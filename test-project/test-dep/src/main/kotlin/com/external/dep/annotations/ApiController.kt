package com.external.dep.annotations

import org.springframework.core.annotation.AliasFor
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Combines @RestController and @RequestMapping into a single annotation.
 * This tests that the TypeScript generator can handle meta-annotations with @AliasFor
 * when loading types from external jars.
 *
 * Usage:
 * ```
 * @ApiController("/api/users")
 * class UserApi { ... }
 * ```
 *
 * @param value The request mapping path (e.g., "/api/users")
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@RestController
@RequestMapping
annotation class ApiController(
    @get:AliasFor(annotation = RequestMapping::class, attribute = "path")
    val value: String
)
