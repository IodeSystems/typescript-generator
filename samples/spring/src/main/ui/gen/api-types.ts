import type {Dayjs} from 'dayjs'
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Create}
 * METHOD ref:
 * - {@link EventApi#create}
 */
export type EventApiCreate = {
  loc: RefLoc
  event: EventApiEvent
}
/**
 * Jvm {@link com.iodesystems.web.api.models.Ref$Loc}
 */
export type RefLoc = Ref & {
  "_type": "Loc"
}
/**
 * Jvm {@link com.iodesystems.web.api.models.Ref}
 * TYPE ref:
 * - {@link RefBu}
 * - {@link RefOrg}
 * - {@link RefLoc}
 */
export type Ref = {
  buId: number
  orgId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.web.api.models.Ref}
 * METHOD ref:
 * - {@link SampleApi#getRef}
 */
export type RefUnion = Ref & (RefBu | RefLoc | RefOrg)
/**
 * Jvm {@link com.iodesystems.web.api.models.Ref$Bu}
 */
export type RefBu = Ref & {
  "_type": "Bu"
}
/**
 * Jvm {@link com.iodesystems.web.api.models.Ref$Org}
 */
export type RefOrg = Ref & {
  "_type": "Org"
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Event}
 */
export type EventApiEvent = {
  startAt: OffsetDateTime
  durationMinutes: number
  recurrence?: EventApiEventRecurrence | null | undefined
  data: FullCalendarEventDataUnion
}
/**
 * Jvm {@link java.time.OffsetDateTime}
 */
export type OffsetDateTime = Dayjs
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Event$Recurrence}
 */
export type EventApiEventRecurrence = {
  untilAt: OffsetDateTime
  frequency: EventApiEventRecurrenceFrequency
  interval?: number | undefined
  weekDays?: Array<EventApiEventRecurrenceWeekDay> | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Event$Recurrence$Frequency}
 */
export type EventApiEventRecurrenceFrequency = 'WEEKLY' | 'DAILY' | 'MONTHLY'
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Event$Recurrence$WeekDay}
 */
export type EventApiEventRecurrenceWeekDay = 'M' | 'T' | 'W' | 'TH' | 'F' | 'S' | 'SU'
/**
 * Jvm {@link com.iodesystems.ts.sample.api.model.FullCalendar$Event$Data}
 */
export type FullCalendarEventDataUnion = FullCalendarEventData & (FullCalendarEventDataAvailability | FullCalendarEventDataBooking)
/**
 * Jvm {@link com.iodesystems.ts.sample.api.model.FullCalendar$Event$Data}
 * TYPE ref:
 * - {@link FullCalendarEventDataAvailability}
 * - {@link FullCalendarEventDataBooking}
 */
export type FullCalendarEventData = {
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.model.FullCalendar$Event$Data$Availability}
 */
export type FullCalendarEventDataAvailability = FullCalendarEventData & {
  "_type": "Availability"
  available?: boolean | null | undefined
  availableForOnlineBooking?: boolean | null | undefined
  availableForCalls?: boolean | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.model.FullCalendar$Event$Data$Booking}
 */
export type FullCalendarEventDataBooking = FullCalendarEventData & {
  "_type": "Booking"
  clientId: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Create$Response}
 * METHOD ref:
 * - {@link EventApi#create}
 */
export type EventApiCreateResponseUnion = EventApiCreateResponse & (EventApiCreateResponseCouldNotCreate | EventApiCreateResponseCreated)
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Create$Response}
 * TYPE ref:
 * - {@link EventApiCreateResponseCouldNotCreate}
 * - {@link EventApiCreateResponseCreated}
 */
export type EventApiCreateResponse = {
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Create$Response$CouldNotCreate}
 */
export type EventApiCreateResponseCouldNotCreate = EventApiCreateResponse & {
  "_type": "CouldNotCreate"
  reason: string
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.EventApi$Create$Response$Created}
 */
export type EventApiCreateResponseCreated = EventApiCreateResponse & {
  "_type": "Created"
  scheduledEventId: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add}
 * METHOD ref:
 * - {@link SampleApi#add}
 */
export type SampleApiAdd = {
  a: number
  b: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response}
 * METHOD ref:
 * - {@link SampleApi#add}
 */
export type SampleApiAddResponseUnion = SampleApiAddResponse & (SampleApiAddResponseFailure | SampleApiAddResponseSuccess)
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response}
 * TYPE ref:
 * - {@link SampleApiAddResponseFailure}
 * - {@link SampleApiAddResponseSuccess}
 */
export type SampleApiAddResponse = {
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response$Failure}
 */
export type SampleApiAddResponseFailure = SampleApiAddResponse & {
  "@type": "Failure"
  error: string
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response$Success}
 */
export type SampleApiAddResponseSuccess = SampleApiAddResponse & {
  "@type": "Success"
  result: number
  at?: OffsetDateTime | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping}
 * METHOD ref:
 * - {@link SampleApi#ping}
 */
export type SampleApiPing = {
  message: string
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response}
 * METHOD ref:
 * - {@link SampleApi#ping}
 */
export type SampleApiPingResponseUnion = SampleApiPingResponse & (SampleApiPingResponsePong)
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response}
 * TYPE ref:
 * - {@link SampleApiPingResponsePong}
 */
export type SampleApiPingResponse = {
  at: OffsetDateTime | null
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response$Pong}
 */
export type SampleApiPingResponsePong = SampleApiPingResponse & {
  "@type": "Pong"
  message: string
}
/**
 * Jvm {@link com.iodesystems.web.api.models.SlugRef}
 * METHOD ref:
 * - {@link SampleApi#getSlugRef}
 */
export type SlugRefUnion = SlugRef & (SlugRefBu | SlugRefLoc | SlugRefOrg)
/**
 * Jvm {@link com.iodesystems.web.api.models.SlugRef}
 * TYPE ref:
 * - {@link SlugRefBu}
 * - {@link SlugRefLoc}
 * - {@link SlugRefOrg}
 */
export type SlugRef = {
  orgSlug: string
  buSlug: string
  locSlug: string
}
/**
 * Jvm {@link com.iodesystems.web.api.models.SlugRef$Bu}
 */
export type SlugRefBu = SlugRef & {
  "_type": "Bu"
  buId: number
  orgId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.web.api.models.SlugRef$Loc}
 */
export type SlugRefLoc = SlugRef & {
  "_type": "Loc"
  buId: number
  orgId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.web.api.models.SlugRef$Org}
 */
export type SlugRefOrg = SlugRef & {
  "_type": "Org"
  buId: number
  orgId: number
  locId: number
}
