import type {Dayjs} from 'dayjs'
/**
 * JVM: com.iodesystems.ts.sample.api.SampleApi$Add
 * Referenced by:
 * - com.iodesystems.ts.sample.api.SampleApi.add
 */
export type SampleApiAdd = {
  a: number
  b: number
}

/**
 * JVM: com.iodesystems.ts.sample.api.SampleApi$Add$Response$Failure
 * Referenced by:
 * - com.iodesystems.ts.sample.api.SampleApi.add
 */
export type SampleApiAddResponseFailure = {
  "@type": "Failure"
  error: string
}

/**
 * JVM: com.iodesystems.ts.sample.api.SampleApi$Add$Response$Success
 * Referenced by:
 * - com.iodesystems.ts.sample.api.SampleApi.add
 */
export type SampleApiAddResponseSuccess = {
  "@type": "Success"
  result: number
  at?: Dayjs | null
}
