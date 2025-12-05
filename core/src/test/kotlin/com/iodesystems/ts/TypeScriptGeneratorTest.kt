package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.tsApis
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals


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
    @Ignore
    fun testTypeScriptGenerator() {
        val output = TypeScriptGenerator.build {
            it
                .includeApi<TestApi>()
                .outputDirectory("./tmp")
                .emitLibAsSeparateFile()
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
        }
            .generate()

        val expected = $$"""
              export class TestApi {
                constructor(private opts: ApiOptions = {}) {}
                post(req: TestApiSimple): Promise<TestApiSimpleResponse> {
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

              /**
               * JVM: com.iodesystems.ts.TestApi$Simple
               * Referenced by:
               * - com.iodesystems.ts.TestApi.post
               */
              type TestApiSimple = {
                a?: string | null
                b?: string
                num: number
                flag: boolean
                name: string
                tags: string[]
                points: number[]
                attrs: Record<string, string>
                nested: Record<string, number[][]>
                date: string
                time: string
                at: Dayjs | string
                price: number
                huge: string
              }

              /**
               * JVM: com.iodesystems.ts.Nested$SomeInterface
               * Referenced by:
               * - com.iodesystems.ts.ReferencedType
               * - com.iodesystems.ts.TestApi$Simple$Response$Ok
               */
              type NestedSomeInterface = {
                interfaceValue: string
              }

              /**
               * JVM: com.iodesystems.ts.ReferencedType
               * Referenced by:
               * - com.iodesystems.ts.TestApi.post
               */
              type ReferencedType = {
                circularTypeReference: ReferencedType | null
              } & NestedSomeInterface

              /**
               * JVM: com.iodesystems.ts.TestApi$Simple$Response$Failure
               * Referenced by:
               * - com.iodesystems.ts.TestApi.post
               */
              type TestApiSimpleResponseFailure = {
                _type: "Failure"
                t: ReferencedType
              }

              /**
               * JVM: com.iodesystems.ts.TestApi$Simple$Response$Ok
               * Referenced by:
               * - com.iodesystems.ts.TestApi.post
               */
              type TestApiSimpleResponseOk = {
                _type: "Ok"
                at: Dayjs | string
              } & NestedSomeInterface

              /**
               * JVM: com.iodesystems.ts.TestApi$Simple$Response
               */
              type TestApiSimpleResponse = TestApiSimpleResponseFailure | TestApiSimpleResponseOk

              /**
               * JVM: com.iodesystems.ts.TestApi$GetResult
               * Referenced by:
               * - com.iodesystems.ts.TestApi.get
               */
              type TestApiGetResult = {
                s: string
                ints: number[]
                tags: string[]
                meta: Record<string, string>
                crazy: Record<string, number[][]>
                d: string
                t: string
                odt: Dayjs | string
                bd: number
                bi: string
              }
        """.trimIndent()
        assertEquals(expected, output.tsApis())
    }
}