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

    // Custom interface to avoid exclusion by omitTypes
    interface CustomContainer<T> {
        fun getItems(): List<T>
    }

    // This is a problematic self-referential type
    // class that implements CustomContainer<itself>
    class SelfContainerNode : CustomContainer<SelfContainerNode> {
        private val items = listOf<SelfContainerNode>()
        override fun getItems(): List<SelfContainerNode> = items
    }

    @GetMapping("/container")
    fun getContainer(): SelfContainerNode = error("test")
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
            exception.message?.contains("SelfContainerNode") == true,
            "Expected error to mention SelfContainerNode, got: ${exception.message}"
        )
        assertTrue(
            exception.message?.contains("exclude") == true,
            "Expected error to suggest exclude, got: ${exception.message}"
        )
    }

    @RestController
    @RequestMapping("/excluded/")
    class ExcludedSelfReferentialApi {
        @GetMapping("/container")
        fun getContainer(): SelfReferentialApi.SelfContainerNode? = null
    }

    @Test
    fun excludedSelfReferentialTypeDoesNotError() {
        // When we exclude the self-referential type, it should be treated as 'any'
        // and not cause an error
        val em = emitter(ExcludedSelfReferentialApi::class) {
            exclude("com.iodesystems.ts.SelfReferentialApi\$SelfContainerNode")
        }

        // Should complete without error
        val ts = em.ts()
        // The type should be mapped to 'any' since it's excluded
    }
}
