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
 * Jvm {@link com.iodesystems.ts.sample.model.Ref}
 * METHOD ref:
 * - {@link SampleApi#getRef}
 */
export type RefUnion = Ref & (RefBu | RefLoc | RefOrg)
/**
 * Jvm {@link com.iodesystems.ts.sample.model.Ref}
 * TYPE ref:
 * - {@link RefBu}
 * - {@link RefLoc}
 * - {@link RefOrg}
 */
export type Ref = {
  orgId: number
  buId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.Ref$Bu}
 */
export type RefBu = Ref & {
  "@type": "Bu"
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.Ref$Loc}
 */
export type RefLoc = Ref & {
  "@type": "Loc"
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.Ref$Org}
 */
export type RefOrg = Ref & {
  "@type": "Org"
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.SlugRef}
 * METHOD ref:
 * - {@link SampleApi#getSlugRef}
 */
export type SlugRefUnion = SlugRef & (SlugRefBu | SlugRefLoc | SlugRefOrg)
/**
 * Jvm {@link com.iodesystems.ts.sample.model.SlugRef}
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
 * Jvm {@link com.iodesystems.ts.sample.model.SlugRef$Bu}
 */
export type SlugRefBu = SlugRef & {
  "@type": "Bu"
  orgId: number
  buId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.SlugRef$Loc}
 */
export type SlugRefLoc = SlugRef & {
  "@type": "Loc"
  orgId: number
  buId: number
  locId: number
}
/**
 * Jvm {@link com.iodesystems.ts.sample.model.SlugRef$Org}
 */
export type SlugRefOrg = SlugRef & {
  "@type": "Org"
  orgId: number
  buId: number
  locId: number
}
