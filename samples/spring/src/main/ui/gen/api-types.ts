import type {Dayjs} from 'dayjs'
export type SampleApiAdd = {
  a: number
  b: number
}
export type SampleApiAddResponseFailure = {
  "@type": "Failure"
}
export type SampleApiAddResponseSuccess = {
  "@type": "Success"
}
export type SampleApiAddResponseUnion = SampleApiAddResponseFailure | SampleApiAddResponseSuccess
