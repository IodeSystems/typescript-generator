package com.iodesystems.ts.sample.api.model

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime

object FullCalendar {
  data class Schedule(
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val resourceGroupField: String?,
    val resources: List<Resource>,
    val events: List<Event>,
  )


  data class Event(
    val id: String,
    // Merging
    val groupId: String? = null,
    // All day
    val allDay: Boolean? = null,
    // Consumption
    val resourceIds: List<String>? = null,

    val title: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val data: Data,
    // Recurrsion Rules
    val rrule: String? = null,
    val exdate: List<OffsetDateTime>? = null,

    // Display meta
    val display: Display? = null,
    val backgroundColor: String? = null,
    val borderColor: String? = null,
    val textColor: String? = null,
  ) {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_type")
    sealed interface Data {
      @JsonTypeName("Booking")
      data class Booking(
        val clientId: Long,
        // We will eventually put service/preferences/booking configurations here.
      ) : Data

      @JsonTypeName("Availability")
      data class Availability(
        // If resource can be locked/consumed
        val available: Boolean? = null,
        // If resource is online bookable
        val availableForOnlineBooking: Boolean? = null,
        // If resource is callable
        val availableForCalls: Boolean? = null
      ) : Data
    }

    enum class Display(
      @JsonValue
      val json: String
    ) {
      AUTO("auto"),
      BACKGROUND("background"),
      BLOCK("block"),
      LIST_ITEM("list-item"),
      INVERSE_BACKGROUND("inverse-background"),
      NONE("none")
      ;

      companion object {
        val byJsonValue = entries.associateBy { it.json }

        @JvmStatic
        fun fromValue(value: String) = byJsonValue[value] ?: throw IllegalArgumentException("Unknown value $value")
      }

    }
  }

  data class Resource(
    val id: String,
    val grouping: String?,
    val title: String = id,
    val parentId: String? = null,
  )
}
