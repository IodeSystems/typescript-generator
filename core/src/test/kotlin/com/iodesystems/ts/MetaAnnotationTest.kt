package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.core.annotation.AliasFor
import org.springframework.web.bind.annotation.*
import kotlin.test.Test

/**
 * Combines @RestController and @RequestMapping into a single annotation.
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

/**
 * Combines @JsonTypeInfo with common settings into a single annotation.
 * This is an alias for @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, property = "_type")
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
annotation class JsonUnion(
    @get:AliasFor(annotation = JsonTypeInfo::class, attribute = "property")
    val property: String = "_type"
)

@ApiController("/api/appointments")
class AppointmentController {
    @PostMapping
    fun create(@RequestBody req: String): String = req

    @PostMapping("/cancel")
    fun cancel(@RequestBody req: String): String = req
}

@RestController
@RequestMapping("/api/events")
class EventController {
    /**
     * Sealed interface using @JsonUnion meta-annotation instead of direct @JsonTypeInfo
     */
    @JsonUnion
    sealed interface Event {
        data class Created(val id: String, val name: String) : Event
        data class Updated(val id: String, val changes: Map<String, String>) : Event
        data class Deleted(val id: String) : Event
    }

    /**
     * Custom discriminator property via @JsonUnion
     */
    @JsonUnion(property = "eventType")
    sealed interface CustomEvent {
        data class Start(val timestamp: Long) : CustomEvent
        data class Stop(val timestamp: Long, val reason: String?) : CustomEvent
    }

    @PostMapping
    fun handleEvent(@RequestBody event: Event): String = "ok"

    @PostMapping("/custom")
    fun handleCustomEvent(@RequestBody event: CustomEvent): String = "ok"
}

class MetaAnnotationTest {

    @Test
    fun testApiControllerMetaAnnotation() {
        val em = emitter(AppointmentController::class)
        val content = em.ts().content()

        // The controller should have the base path from @ApiController
        content.assertContains(
            fragment = """fetchInternal(this.opts, "/api/appointments",""",
            why = "create endpoint should have base path /api/appointments"
        )

        content.assertContains(
            fragment = """fetchInternal(this.opts, "/api/appointments/cancel",""",
            why = "cancel endpoint should have base path /api/appointments/cancel"
        )
    }

    @Test
    fun testJsonUnionMetaAnnotation() {
        val em = emitter(EventController::class)
        val content = em.ts().content()

        // Verify Event union is generated with default _type discriminator
        content.assertContains(
            fragment = """"_type": "Created"""",
            why = "Created variant should have _type discriminator from @JsonUnion"
        )
        content.assertContains(
            fragment = """"_type": "Updated"""",
            why = "Updated variant should have _type discriminator"
        )
        content.assertContains(
            fragment = """"_type": "Deleted"""",
            why = "Deleted variant should have _type discriminator"
        )

        // Verify CustomEvent union is generated with custom eventType discriminator
        content.assertContains(
            fragment = """"eventType": "Start"""",
            why = "Start variant should have eventType discriminator from @JsonUnion(property)"
        )
        content.assertContains(
            fragment = """"eventType": "Stop"""",
            why = "Stop variant should have eventType discriminator"
        )

        // Verify union types are created
        content.assertContains(
            fragment = "EventControllerEventUnion",
            why = "Event union type should be generated"
        )
        content.assertContains(
            fragment = "EventControllerCustomEventUnion",
            why = "CustomEvent union type should be generated"
        )
    }
}
