package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/**
 * Tests that enum types used within generics (like Set<WeekDay>) are properly
 * registered and emitted in the output.
 *
 * This was a bug where enums weren't cached, so when converted to inline references
 * for use in generics, the original TsType.Enum was lost and never registered.
 */
class EnumInGenericsTest {

    enum class WeekDay {
        M, T, W, TH, F, S, SU
    }

    enum class Priority {
        LOW, MEDIUM, HIGH
    }

    data class Schedule(
        val name: String,
        val days: Set<WeekDay>,
        val priorities: List<Priority>? = null
    )

    @RestController
    @RequestMapping("/schedule")
    class ScheduleController {
        @GetMapping
        fun getSchedule(): Schedule = error("test")
    }

    @Test
    fun enumInSet_shouldBeRegistered() {
        val em = emitter(ScheduleController::class)
        val ts = em.ts().content()

        // WeekDay enum should be emitted
        ts.assertContains(
            fragment = "export type EnumInGenericsTestWeekDay = 'M' | 'T' | 'W' | 'TH' | 'F' | 'S' | 'SU'",
            why = "WeekDay enum should be registered and emitted"
        )

        // Priority enum should be emitted
        ts.assertContains(
            fragment = "export type EnumInGenericsTestPriority = 'LOW' | 'MEDIUM' | 'HIGH'",
            why = "Priority enum should be registered and emitted"
        )

        // Schedule should reference the enum types
        ts.assertContains(
            fragment = "days: Array<EnumInGenericsTestWeekDay>",
            why = "Schedule.days should reference WeekDay enum"
        )
        ts.assertContains(
            fragment = "priorities?: Array<EnumInGenericsTestPriority>",
            why = "Schedule.priorities should reference Priority enum"
        )
    }
}
