package com.iodesystems.ts

import org.junit.Assert.assertThrows
import org.springframework.web.bind.annotation.*
import kotlin.test.Test
import kotlin.test.assertTrue

@RestController
@RequestMapping("/err1")
class CollisionControllerA {
    data class A(val x: String)

    @PostMapping
    fun post(@RequestBody req: A): A = req
}

@RestController
@RequestMapping("/err2")
class CollisionControllerB {
    data class B(val x: String)

    @PostMapping
    fun post(@RequestBody req: B): B = req
}

@RestController
@RequestMapping("/dupq")
class DuplicateQueryParamController {
    @GetMapping
    fun list(@RequestParam("x") a: String, @RequestParam("x") b: Int): String = "ok"
}

@RestController
@RequestMapping("/nobody")
class PostWithoutBodyController {
    @PostMapping
    fun create(): String = "fail"
}

@RestController
@RequestMapping("/void")
class VoidReturnController {
    @PostMapping
    fun create(@RequestBody s: String) {
        // returns Unit
    }
}

class ErrorsAndVoidBehaviorTest {

    @Test
    fun customNamingCollisionProducesHelpfulError() {
        val ex = assertThrows(IllegalStateException::class.java) {
            TypeScriptGenerator.build {
                it
                    .includeApi(
                        CollisionControllerA::class,
                        CollisionControllerB::class,
                    )
                    .addTypeNameReplacement(".*", "Fixed")
            }.generate()
        }
        assertTrue(ex.message!!.contains("Type alias name collision"))
    }

    @Test
    fun duplicateQueryParamNamesError() {
        val ex = assertThrows(IllegalStateException::class.java) {
            TypeScriptGenerator.build {
                it.includeApi(DuplicateQueryParamController::class)
            }.generate()
        }
        assertTrue(ex.message!!.contains("Duplicate query parameter name"))
    }

    @Test
    fun postWithoutBodyErrors() {
        val ex = assertThrows(IllegalStateException::class.java) {
            TypeScriptGenerator.build {
                it.includeApi<PostWithoutBodyController>()
            }.generate()
        }
        assertTrue(ex.message!!.contains("must declare a @RequestBody"))
    }

    @Test
    fun voidReturnDoesNotDecodeJson() {
        val out = TypeScriptGenerator.build {
            it.includeApi<VoidReturnController>()
        }.generate()
        val ts = out.tsApis()
        assertTrue(ts.contains(").then(r=>undefined as any)"), "Void return should not call r.json()\n$ts")
    }
}
