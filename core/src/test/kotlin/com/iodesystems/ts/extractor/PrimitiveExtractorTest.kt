package com.iodesystems.ts.extractor

import com.iodesystems.ts.lib.Asserts.assertEq
import com.iodesystems.ts.lib.Asserts.assertIsEmpty
import com.iodesystems.ts.lib.Asserts.assertNonNull
import com.iodesystems.ts.lib.Asserts.assertSingle
import com.iodesystems.ts.lib.Asserts.assertType
import com.iodesystems.ts.lib.TestUtils.extract
import com.iodesystems.ts.model.TsType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

class PrimitiveExtractorTest {
    @RestController
    @RequestMapping("/kotlin-primitives")
    class KotlinPrimitiveController {
        @PostMapping("/boolean")
        fun bool(@RequestBody req: Boolean) = req

        @PostMapping("/byte")
        fun byte(@RequestBody req: Byte) = req

        @PostMapping("/short")
        fun short(@RequestBody req: Short) = req

        @PostMapping("/int")
        fun int(@RequestBody req: Int) = req

        @PostMapping("/long")
        fun long(@RequestBody req: Long) = req

        @PostMapping("/float")
        fun float(@RequestBody req: Float) = req

        @PostMapping("/double")
        fun double(@RequestBody req: Double) = req

        @PostMapping("/char")
        fun char(@RequestBody req: Char) = req

        @PostMapping("/string")
        fun string(@RequestBody req: String) = req

        // Collections
        @PostMapping("/listInt")
        fun listInt(@RequestBody req: List<Int>) = req

        @PostMapping("/setString")
        fun setString(@RequestBody req: Set<String>) = req

        @PostMapping("/mapStringLong")
        fun mapStringLong(@RequestBody req: Map<String, Long>) = req
    }

    @Test
    fun kotlinPrimitives_and_Collections_are_mapped() {
        val extraction = extract<KotlinPrimitiveController>()
        val api = extraction.apis.assertSingle("One Kotlin primitive API expected")

        val expected = mapOf(
            "bool" to "boolean",
            "byte" to "number",
            "short" to "number",
            "int" to "number",
            "long" to "number",
            "float" to "number",
            "double" to "number",
            "char" to "string",
            "string" to "string",
        )

        // Check primitive mappings
        expected.forEach { (methodName, ts) ->
            val m = api.apiMethods.firstOrNull { it.name == methodName }
                .assertNonNull("Missing method '$methodName'")
            m.requestBodyType.assertNonNull("$methodName should have request body")
                .let { t ->
                    t.name.assertEq(ts, "$methodName request should be $ts")
                    t.nullable.assertEq(false, "$methodName should not be nullable")
                    t.optional.assertEq(false, "$methodName should not be optional")
                }
            m.responseBodyType.assertNonNull("$methodName should have response body")
                .let { t ->
                    t.name.assertEq(ts, "$methodName response should be $ts")
                    t.nullable.assertEq(false, "$methodName response should not be nullable")
                    t.optional.assertEq(false, "$methodName response should not be optional")
                }
        }

        // Collections
        run {
            val m = api.apiMethods.firstOrNull { it.name == "listInt" }
                .assertNonNull("Missing method 'listInt'")
            m.responseBodyType.assertNonNull("listInt response missing")
                .assertType<TsType.Inline>("listInt should be inline")
                .let { t ->
                    t.name.assertEq("Array<T>", "Kotlin List should be Array<T>")
                    t.generics["T"].assertNonNull("List<T> missing T")
                        .let { el -> el.name.assertEq("number", "List<Int> should be number") }
                }
        }

        run {
            val m = api.apiMethods.firstOrNull { it.name == "setString" }
                .assertNonNull("Missing method 'setString'")
            m.responseBodyType.assertNonNull("setString response missing")
                .assertType<TsType.Inline>("setString should be inline")
                .let { t ->
                    t.name.assertEq("Array<T>", "Kotlin Set should be Array<T>")
                    t.generics["T"].assertNonNull("Array<T> missing T")
                        .let { el -> el.name.assertEq("string", "Array<String> should be string") }
                }
        }

        run {
            val m = api.apiMethods.firstOrNull { it.name == "mapStringLong" }
                .assertNonNull("Missing method 'mapStringLong'")
            m.responseBodyType.assertNonNull("mapStringLong response missing")
                .assertType<TsType.Inline>("mapStringLong should be inline")
                .let { t ->
                    t.name.assertEq("Record<K,V>", "Kotlin Map should be Record<K,V>")
                    t.generics["K"].assertNonNull("Map<K,V> missing K")
                        .let { k -> k.name.assertEq("string", "Map<String,*> key should be string") }
                    t.generics["V"].assertNonNull("Map<K,V> missing V")
                        .let { v -> v.name.assertEq("number", "Map<*,Long> value should be number") }
                }
        }

        // No alias/object/union types generated for primitives and these collections
        extraction.types.assertIsEmpty("No generated types expected for primitives and simple collections")
    }

    @Test
    fun javaPrimitives_and_Collections_are_mapped() {
        val extraction = extract<JavaPrimitiveController>()
        val api = extraction.apis.assertSingle("One Java primitive API expected")

        val expected = mapOf(
            "booleanPrimitive" to "boolean",
            "booleanWrapper" to "boolean",
            "bytePrimitive" to "number",
            "byteWrapper" to "number",
            "shortPrimitive" to "number",
            "shortWrapper" to "number",
            "intPrimitive" to "number",
            "intWrapper" to "number",
            "longPrimitive" to "number",
            "longWrapper" to "number",
            "floatPrimitive" to "number",
            "floatWrapper" to "number",
            "doublePrimitive" to "number",
            "doubleWrapper" to "number",
            "charPrimitive" to "string",
            "charWrapper" to "string",
            "string" to "string",
        )

        expected.forEach { (methodName, ts) ->
            val m = api.apiMethods.firstOrNull { it.name == methodName }
                .assertNonNull("Missing Java method '$methodName'")
            m.responseBodyType.assertNonNull("$methodName response should exist")
                .let { t ->
                    t.name.assertEq(ts, "Java $methodName response should be $ts")
                    t.nullable.assertEq(false, "Java $methodName should not be nullable")
                    t.optional.assertEq(false, "Java $methodName should not be optional")
                }
            m.requestBodyType.assertNonNull("$methodName request should exist")
                .let { t -> t.name.assertEq(ts, "Java $methodName request should be $ts") }
        }

        // Collections
        run {
            val m = api.apiMethods.firstOrNull { it.name == "listInteger" }
                .assertNonNull("Missing Java method 'listInteger'")
            m.responseBodyType.assertNonNull("listInteger response missing")
                .assertType<TsType.Inline>("listInteger should be inline")
                .let { t ->
                    t.name.assertEq("Array<T>", "Java List should be Array<T>")
                    t.generics["T"].assertNonNull("List<T> missing T")
                        .let { el -> el.name.assertEq("number", "List<Integer> should be number") }
                }
        }

        run {
            val m = api.apiMethods.firstOrNull { it.name == "setString" }
                .assertNonNull("Missing Java method 'setString'")
            m.responseBodyType.assertNonNull("setString response missing")
                .assertType<TsType.Inline>("setString should be inline")
                .let { t ->
                    t.name.assertEq("Array<T>", "Java Set should be Set<T>")
                    t.generics["T"].assertNonNull("Array<T> missing T")
                        .let { el -> el.name.assertEq("string", "Array<String> should be string") }
                }
        }

        run {
            val m = api.apiMethods.firstOrNull { it.name == "mapStringDouble" }
                .assertNonNull("Missing Java method 'mapStringDouble'")
            m.responseBodyType.assertNonNull("mapStringDouble response missing")
                .assertType<TsType.Inline>("mapStringDouble should be inline")
                .let { t ->
                    t.name.assertEq("Record<K,V>", "Java Map should be Record<K,V>")
                    t.generics["K"].assertNonNull("Map<K,V> missing K")
                        .let { k -> k.name.assertEq("string", "Map<String,*> key should be string") }
                    t.generics["V"].assertNonNull("Map<K,V> missing V")
                        .let { v -> v.name.assertEq("number", "Map<*,Double> value should be number") }
                }
        }

        extraction.types.assertIsEmpty("No generated types expected for primitives and simple collections (Java)")
    }
}
