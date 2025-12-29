package com.iodesystems.ts

import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RestController
@RequestMapping("/self-ref/")
class SelfReferentialApi {

    // This is a problematic self-referential type
    // class that implements Iterable<itself>
    class SelfIterableNode : Iterable<SelfIterableNode> {
        private val items = listOf<SelfIterableNode>()
        override fun iterator(): Iterator<SelfIterableNode> = items.iterator()
    }

    @GetMapping("/iterable")
    fun getIterable(): SelfIterableNode = error("test")
}

class SelfReferentialTypeTest {

    @Test
    fun selfReferentialIterableThrowsError() {
        val exception = assertFailsWith<IllegalStateException> {
            emitter(SelfReferentialApi::class)
        }

        assertTrue(
            exception.message?.contains("Self-referential type detected") == true,
            "Expected error about self-referential type, got: ${exception.message}"
        )
        assertTrue(
            exception.message?.contains("SelfIterableNode") == true,
            "Expected error to mention SelfIterableNode, got: ${exception.message}"
        )
        assertTrue(
            exception.message?.contains("excludeTypes") == true ||
            exception.message?.contains("omitTypes") == true,
            "Expected error to suggest excludeTypes or omitTypes, got: ${exception.message}"
        )
    }

    @RestController
    @RequestMapping("/excluded/")
    class ExcludedSelfReferentialApi {
        @GetMapping("/iterable")
        fun getIterable(): SelfReferentialApi.SelfIterableNode? = null
    }

    @Test
    fun excludedSelfReferentialTypeDoesNotError() {
        // When we exclude the self-referential type, it should be treated as 'any'
        // and not cause an error
        val em = emitter(ExcludedSelfReferentialApi::class) {
            excludeTypes("com.iodesystems.ts.SelfReferentialApi\$SelfIterableNode")
        }

        // Should complete without error
        val ts = em.ts()
        // The type should be mapped to 'any' since it's excluded
    }
}
