package com.iodesystems.ts.lib

import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object Asserts {
    private val om by lazy { ObjectMapper().writerWithDefaultPrettyPrinter() }
    fun Any.toJson(): String = om.writeValueAsString(this)
    fun <T> List<T>.assertIsEmpty(explanation: String) {
        size.assertEq(0, explanation)
    }

    fun <K, T> Map<K, T>.assertSingleKey(key: K, explanation: String): T {
        size.assertEq(1, explanation)
        return this.values.first()
    }

    fun <T> List<T>.assertSingle(explanation: String): T {
        size.assertEq(1, explanation)
        return this[0]
    }

    fun <T : Any> T?.assertNonNull(explanation: String): T {
        assertTrue(this != null, explanation)
        return this
    }

    inline fun <reified T> Any.assertType(explanation: String): T {
        if (T::class.java.isAssignableFrom(this::class.java)) return this as T
        assertTrue(false, "$explanation: ${this::class.qualifiedName} is not assignable to ${T::class.qualifiedName}")
        error("This should not be possible")
    }

    fun <T> T.assertEq(expect: T, explanation: String): T {
        assertTrue(this != null, explanation)
        assertEquals(expect, this, explanation)
        return this
    }

    fun String.assertContains(fragment: String, why: String) = kotlin.test.assertContains(
        charSequence = this,
        other = fragment,
        ignoreCase = false,
        message = why
    )
}