package com.iodesystems.ts

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

@ApiController("/api/appointments")
class AppointmentController {
    @PostMapping
    fun create(@RequestBody req: String): String = req

    @PostMapping("/cancel")
    fun cancel(@RequestBody req: String): String = req
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
}
