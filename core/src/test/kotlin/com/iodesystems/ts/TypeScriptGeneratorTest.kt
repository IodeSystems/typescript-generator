package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertEq
import com.iodesystems.ts.lib.TestUtils.content
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.Test


class ReferencedType(
    val circularTypeReference: ReferencedType?,
) : Nested.SomeInterface

object Nested {
    interface SomeInterface {
        fun getInterfaceValue(): String = this::class.java.simpleName
    }
}

@RestController
@RequestMapping("/api/test-api")
class TestApi {

    data class Simple(
        val a: String? = null,
        val b: String = "asdf",
        val num: Int,
        val flag: Boolean,
        val name: String,
        val tags: Set<String>,
        val points: List<Double>,
        val attrs: Map<String, String>,
        val nested: Map<String, List<Set<Long>>>,
        val date: LocalDate,
        val time: LocalTime,
        val at: OffsetDateTime,
        val price: BigDecimal,
        val huge: BigInteger,
    ) {
        @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME, property = "_type")
        sealed interface Response {
            data class Ok(
                val at: OffsetDateTime
            ) : Response, Nested.SomeInterface

            object Failure : Response {
                val t: ReferencedType = ReferencedType(null)
            }
        }
    }

    @PostMapping
    fun post(@RequestBody req: Simple): Simple.Response = error("test method!")

    data class GetResult(
        val s: String,
        val ints: List<Int>,
        val tags: Set<String>,
        val meta: Map<String, String>,
        val crazy: Map<String, List<Set<Long>>>,
        val d: LocalDate,
        val t: LocalTime,
        val odt: OffsetDateTime,
        val bd: BigDecimal,
        val bi: BigInteger,
    )

    @RequestMapping("some-path")
    fun get(): GetResult = error("test method!")
}

class TypeScriptGeneratorTest {

    @Test
    fun testTypeScriptGenerator() {
        val em = emitter(TestApi::class) {
            outputDirectory("./tmp")
                .emitLibAsSeparateFile()
                .mapType(OffsetDateTime::class, "Dayjs | string")
                .mapType(LocalDate::class, "string")
                .mapType(LocalTime::class, "string")
                .externalImportLines("Dayjs" to "import type {Dayjs} from 'dayjs'")
        }
        val content = em.ts().content(includeLib = true)
        val expected = $$"""
        //<api-lib.ts>
        export type RequestInterceptor = (input: RequestInfo, init: RequestInit) => Promise<[RequestInfo, RequestInit]> | [RequestInfo, RequestInit]
        export type ResponseInterceptor = (response: Promise<Response>) => Promise<Response>
        export type ApiOptions = {
          baseUrl?: string
          requestInterceptor?: RequestInterceptor
          responseInterceptor?: ResponseInterceptor
          fetchImpl?: typeof fetch
        }

        export type AbortablePromise<T> = (() => void) & {
          abort: () => void;
          then<TResult1 = T, TResult2 = never>(
            onfulfilled?: ((value: T) => TResult1 | PromiseLike<TResult1>) | undefined | null,
            onrejected?: ((reason: any) => TResult2 | PromiseLike<TResult2>) | undefined | null
          ): AbortablePromise<TResult1 | TResult2>;
          catch<TResult = never>(
            onrejected?: ((reason: any) => TResult | PromiseLike<TResult>) | undefined | null
          ): AbortablePromise<T | TResult>;
          finally(onfinally?: (() => void) | undefined | null): AbortablePromise<T>;
        } & Promise<T>

        export function abortable<T>(
          promise: Promise<T>,
          controller: AbortController,
          onAbort: () => void
        ): AbortablePromise<T> {
          const abort = () => {
            onAbort()
            controller.abort("Request cancelled")
          }

          const abortablePromise = abort as AbortablePromise<T>
          abortablePromise.abort = abort

          // Override then/catch/finally to return AbortablePromise
          const originalThen = promise.then.bind(promise)
          abortablePromise.then = function<TResult1 = T, TResult2 = never>(
            onfulfilled?: ((value: T) => TResult1 | PromiseLike<TResult1>) | undefined | null,
            onrejected?: ((reason: any) => TResult2 | PromiseLike<TResult2>) | undefined | null
          ): AbortablePromise<TResult1 | TResult2> {
            return abortable(originalThen(onfulfilled, onrejected), controller, onAbort)
          } as any

          const originalCatch = promise.catch.bind(promise)
          abortablePromise.catch = function<TResult = never>(
            onrejected?: ((reason: any) => TResult | PromiseLike<TResult>) | undefined | null
          ): AbortablePromise<T | TResult> {
            return abortable(originalCatch(onrejected), controller, onAbort)
          } as any

          const originalFinally = promise.finally.bind(promise)
          abortablePromise.finally = function(
            onfinally?: (() => void) | undefined | null
          ): AbortablePromise<T> {
            return abortable(originalFinally(onfinally), controller, onAbort)
          } as any

          return abortablePromise
        }

        export function flattenQueryParams(path: string, params?: any, prefix: string|null = null): string {
          if (params == null) return path
          const out = new URLSearchParams()
          const appendVal = (k: string, v: any) => { if (v === undefined || v === null) return; out.append(k, String(v)) }
          const walk = (pfx: string, val: any) => {
            if (val === null || val === undefined) return
            if (Array.isArray(val)) {
              for (let i = 0; i < val.length; i++) { walk(pfx + "[" + i + "]", val[i]) }
            } else if (typeof val === 'object' && !(val instanceof Date) && !(val instanceof Blob)) {
              for (const k of Object.keys(val)) {
                const next = pfx ? (pfx + "." + k) : k
                walk(next, (val as any)[k])
              }
            } else {
              appendVal(pfx, val)
            }
          }
          if (prefix) {
            walk(prefix, params)
          } else {
            for (const k of Object.keys(params)) walk(k, (params as any)[k])
          }
          const qs = out.toString()
          return qs ? (path + "?" + qs) : path
        }

        export function fetchInternal(opts: ApiOptions, path: string, init: RequestInit): AbortablePromise<Response> {
          const controller = new AbortController()
          const baseUrl = opts.baseUrl ?? ""
          let input: RequestInfo = baseUrl + path
          let options: RequestInit = { ...init, signal: controller.signal }

          const performFetch = async (): Promise<Response> => {
            if (opts.requestInterceptor) {
              const out = await opts.requestInterceptor(input, options)
              input = out[0]; options = out[1]
            }
            const f = opts.fetchImpl ?? fetch
            const res = f(input, options)
            if (opts.responseInterceptor) {
              return opts.responseInterceptor(res)
            } else {
              return res
            }
          }

          return abortable(performFetch(), controller, () => {})
        }

        //</api-lib.ts>
        //<api.ts>
        //import type {Dayjs} from 'dayjs'
        /**
         * Jvm {@link java.math.BigDecimal}
         */
        export type BigDecimal = number
        /**
         * Jvm {@link java.math.BigInteger}
         */
        export type BigInteger = number & {
          lowestSetBit: number
        }
        /**
         * Jvm {@link java.time.LocalDate}
         */
        export type LocalDate = string
        /**
         * Jvm {@link java.time.LocalTime}
         */
        export type LocalTime = string
        /**
         * Jvm {@link com.iodesystems.ts.Nested$SomeInterface}
         * TYPE ref:
         * - {@link ReferencedType}
         */
        export type NestedSomeInterface = {
          interfaceValue: string
        }
        /**
         * Jvm {@link java.time.OffsetDateTime}
         */
        export type OffsetDateTime = Dayjs | string
        /**
         * Jvm {@link com.iodesystems.ts.ReferencedType}
         */
        export type ReferencedType = NestedSomeInterface & {
          circularTypeReference: ReferencedType | null
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$GetResult}
         * METHOD ref:
         * - {@link TestApi#get}
         */
        export type TestApiGetResult = {
          bd: BigDecimal
          bi: BigInteger
          crazy: Record<string,Array<Array<number>>>
          d: LocalDate
          ints: Array<number>
          meta: Record<string,string>
          odt: OffsetDateTime
          s: string
          t: LocalTime
          tags: Array<string>
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$Simple}
         * METHOD ref:
         * - {@link TestApi#post}
         */
        export type TestApiSimple = {
          a?: string | null | undefined
          at: OffsetDateTime
          attrs: Record<string,string>
          b?: string | undefined
          date: LocalDate
          flag: boolean
          huge: BigInteger
          name: string
          nested: Record<string,Array<Array<number>>>
          num: number
          points: Array<number>
          price: BigDecimal
          tags: Array<string>
          time: LocalTime
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$Simple$Response}
         * TYPE ref:
         * - {@link TestApiSimpleResponseFailure}
         * - {@link TestApiSimpleResponseOk}
         */
        export type TestApiSimpleResponse = {
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$Simple$Response$Failure}
         */
        export type TestApiSimpleResponseFailure = TestApiSimpleResponse & {
          "_type": "Failure"
          t: ReferencedType
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$Simple$Response$Ok}
         */
        export type TestApiSimpleResponseOk = TestApiSimpleResponse & {
          "_type": "Ok"
          at: OffsetDateTime
          interfaceValue: string
        }
        /**
         * Jvm {@link com.iodesystems.ts.TestApi$Simple$Response}
         * METHOD ref:
         * - {@link TestApi#post}
         */
        export type TestApiSimpleResponseUnion = TestApiSimpleResponse & (TestApiSimpleResponseFailure | TestApiSimpleResponseOk)
        //import { AbortablePromise, ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'
        /**
         * Jvm {@link com.iodesystems.ts.TestApi}
         */
        export class TestApi {
          constructor(private opts: ApiOptions = {}) {}
          post(req: TestApiSimple): AbortablePromise<TestApiSimpleResponseUnion> {
            return fetchInternal(this.opts, "/api/test-api", {
              method: "POST",
              headers: {'Content-Type': 'application/json'},
              body: JSON.stringify(req)
            }).then(r=>r.json())
          }
          get(): AbortablePromise<TestApiGetResult> {
            return fetchInternal(this.opts, "/api/test-api/some-path", {
              method: "GET"
            }).then(r=>r.json())
          }
        }

        //</api.ts>
        
        """.trimIndent()
        content.assertEq(expected, "...")
    }
}