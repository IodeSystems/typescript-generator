package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.Optional
import kotlin.test.Test

@RestController
@RequestMapping("/test/")
class OptionalGenericController {

    // Java-style response with getter returning Optional<Boolean>
    class JavaStyleResponse {
        fun getAppidExclude(): Optional<Boolean> = Optional.empty()
        fun getName(): Optional<String> = Optional.empty()
        fun getCount(): Optional<Int> = Optional.empty()
    }

    @GetMapping
    fun get(): JavaStyleResponse = JavaStyleResponse()
}

class OptionalGenericTest {

    @Test
    fun `Optional Boolean should emit boolean or null`() {
        val em = emitter(OptionalGenericController::class)
        val content = em.ts().content()

        // The bug was: we got "T | null" instead of "boolean | null"
        content.assertContains(
            fragment = "appidExclude: boolean | null",
            why = "Optional<Boolean> should be reified to boolean | null"
        )

        content.assertContains(
            fragment = "name: string | null",
            why = "Optional<String> should be reified to string | null"
        )

        content.assertContains(
            fragment = "count: number | null",
            why = "Optional<Int> should be reified to number | null"
        )
    }
}
