package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.emitter.EmitterTest.Companion.content
import com.iodesystems.ts.emitter.EmitterTest.Companion.emitter
import com.iodesystems.ts.lib.Asserts.assertEq
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
        val em = emitter<TestApi> {
            outputDirectory("./tmp")
                .emitLibAsSeparateFile()
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
        }

        val content = em.ts().content(includeLib = true)


        val expected = $$"""
            api-lib.ts:
            =========
            export type RequestInterceptor = (input: RequestInfo, init: RequestInit) => Promise<[RequestInfo, RequestInit]> | [RequestInfo, RequestInit]
            export type ResponseInterceptor = (response: Promise<Response>) => Promise<Response>
            export type ApiOptions = {
              baseUrl?: string
              requestInterceptor?: RequestInterceptor
              responseInterceptor?: ResponseInterceptor
              fetchImpl?: typeof fetch
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

            export async function fetchInternal(opts: ApiOptions, path: string, init: RequestInit): Promise<Response> {
              const baseUrl = opts.baseUrl ?? ""
              let input: RequestInfo = baseUrl + path
              let options: RequestInit = init
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

            =========
            api.ts:
            =========
            export type TestApiSimple = {
              a?: string | null | undefined
              b?: string | undefined
              num: number
              flag: boolean
              name: string
              tags: Set<string>
              points: Array<number>
              attrs: Record<string,string>
              nested: Record<string,Array<Set<number>>>
              date: string
              time: string
              at: Dayjs | string
              price: number
              huge: string
            }
            export type TestApiSimpleResponse = {
            }
            export type NestedSomeInterface = {
              interfaceValue: string
            }
            export type ReferencedType = NestedSomeInterface & {
              circularTypeReference: ReferencedType | null
            }
            export type TestApiSimpleResponseFailure = TestApiSimpleResponse & {
              "_type": "Failure"
              t: ReferencedType
            }
            export type TestApiSimpleResponseOk = TestApiSimpleResponse & NestedSomeInterface & {
              "_type": "Ok"
              at: Dayjs | string
            }
            export type TestApiSimpleResponseUnion = TestApiSimpleResponse & (TestApiSimpleResponseFailure | TestApiSimpleResponseOk)
            export type TestApiGetResult = {
              s: string
              ints: Array<number>
              tags: Set<string>
              meta: Record<string,string>
              crazy: Record<string,Array<Set<number>>>
              d: string
              t: string
              odt: Dayjs | string
              bd: number
              bi: string
            }
            import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'
            export class TestApi {
              constructor(private opts: ApiOptions = {}) {}
              post(req: TestApiSimple): Promise<TestApiSimpleResponseUnion> {
                return fetchInternal(this.opts, "/api/test-api", {
                  method: "POST",
                  headers: {'Content-Type': 'application/json'},
                  body: JSON.stringify(req)
                }).then(r=>r.json())
              }
              get(): Promise<TestApiGetResult> {
                return fetchInternal(this.opts, "/api/test-api/some-path", {
                  method: "GET"
                }).then(r=>r.json())
              }
            }

            =========

        """.trimIndent()
        content.assertEq(expected, "...")
    }
}