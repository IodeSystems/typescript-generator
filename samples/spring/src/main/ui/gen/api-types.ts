import type {Dayjs} from 'dayjs'
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
 * Jvm {@link java.time.OffsetDateTime}
 */
export type OffsetDateTime = Dayjs
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
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response$Pong}
 */
export type SampleApiPingResponsePong = SampleApiPingResponse & {
  "@type": "Pong"
  message: string
  at?: OffsetDateTime | null | undefined
}
