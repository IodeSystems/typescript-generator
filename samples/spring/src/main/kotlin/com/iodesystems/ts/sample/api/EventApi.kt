package com.iodesystems.ts.sample.api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.iodesystems.ts.sample.api.model.FullCalendar
import com.iodesystems.web.api.models.Ref
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.time.OffsetDateTime


@RestController
@RequestMapping("/api/orgs/events")
class EventApi(
) {
    data class Event(
        val startAt: OffsetDateTime,
        val durationMinutes: Int,
        val recurrence: Recurrence? = null,
        val data: FullCalendar.Event.Data
    ) {
        data class Recurrence(
            val untilAt: OffsetDateTime,
            val frequency: Frequency,
            val interval: Int = 1,
            val weekDays: Set<WeekDay>? = null
        ) {
            enum class WeekDay {
                M, T, W, TH, F, S, SU;

                fun dow(): DayOfWeek {
                    return when (this) {
                        M -> DayOfWeek.MONDAY
                        T -> DayOfWeek.TUESDAY
                        W -> DayOfWeek.WEDNESDAY
                        TH -> DayOfWeek.THURSDAY
                        F -> DayOfWeek.FRIDAY
                        S -> DayOfWeek.SATURDAY
                        SU -> DayOfWeek.SUNDAY
                    }
                }
            }

            enum class Frequency {
                WEEKLY,
                DAILY,
                MONTHLY;
            }
        }
    }

    data class Create(
        val loc: Ref.Loc,
        val event: Event,
    ) {
        @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_type")
        sealed interface Response {
            @JsonTypeName("Created")
            data class Created(val scheduledEventId: Long) : Response

            @JsonTypeName("CouldNotCreate")
            data class CouldNotCreate(val reason: String) : Response
        }
    }

    @PostMapping("/create")
    fun create(
        @RequestBody req: Create,
    ): Create.Response {
        TODO()
    }
}
