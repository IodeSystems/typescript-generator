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
 * TYPE ref:
 * - {@link SampleApiAddResponseFailure}
 * - {@link SampleApiAddResponseSuccess}
 */
export type SampleApiAddResponse = {
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response$Failure}
 * TYPE ref:
 * - {@link SampleApiAddResponseUnion}
 */
export type SampleApiAddResponseFailure = SampleApiAddResponse & {
  "@type": "Failure"
  error: string
}
/**
 * Jvm {@link java.time.OffsetDateTime}
 * TYPE ref:
 * - {@link SampleApiAddResponseSuccess}
 * - {@link SampleApiPingResponsePong}
 */
export type OffsetDateTime = Dayjs
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response$Success}
 * TYPE ref:
 * - {@link SampleApiAddResponseUnion}
 */
export type SampleApiAddResponseSuccess = SampleApiAddResponse & {
  "@type": "Success"
  result: number
  at?: OffsetDateTime | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Add$Response#Union}
 * METHOD ref:
 * - {@link SampleApi#add}
 */
export type SampleApiAddResponseUnion = SampleApiAddResponse & (SampleApiAddResponseFailure | SampleApiAddResponseSuccess)
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
 * TYPE ref:
 * - {@link SampleApiPingResponsePong}
 */
export type SampleApiPingResponse = {
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response$Pong}
 * TYPE ref:
 * - {@link SampleApiPingResponseUnion}
 */
export type SampleApiPingResponsePong = SampleApiPingResponse & {
  "@type": "Pong"
  message: string
  at?: OffsetDateTime | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.sample.api.SampleApi$Ping$Response#Union}
 * METHOD ref:
 * - {@link SampleApi#ping}
 */
export type SampleApiPingResponseUnion = SampleApiPingResponse & (SampleApiPingResponsePong)
