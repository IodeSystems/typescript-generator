import type {Dayjs} from 'dayjs'
export type SampleApiAdd = {
  a: number
  b: number
}
export type SampleApiAddResponse = {
}
export type SampleApiAddResponseFailure = SampleApiAddResponse & {
  "@type": "Failure"
  error: string
}
export type SampleApiAddResponseSuccess = SampleApiAddResponse & {
  "@type": "Success"
  result: number
  at?: Dayjs | null | undefined
}
export type SampleApiAddResponseUnion = SampleApiAddResponse & (SampleApiAddResponseFailure | SampleApiAddResponseSuccess)
export type SampleApiPing = {
  message: string
}
export type SampleApiPingResponse = {
}
export type SampleApiPingResponsePong = SampleApiPingResponse & {
  "@type": "Pong"
  message: string
  at?: Dayjs | null | undefined
}
export type SampleApiPingResponseUnion = SampleApiPingResponse & (SampleApiPingResponsePong)
