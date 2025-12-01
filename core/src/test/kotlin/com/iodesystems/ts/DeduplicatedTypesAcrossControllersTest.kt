package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.TypeScriptGenerator
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * This test ensures that when two controllers use request/response types
 * that implement the same interface and share a referenced complex type,
 * the TypeScript generator emits that shared type only once.
 */

// Shared interface implemented by request objects and sealed responses
interface Marker

// A shared complex referenced type used by both controllers' responses
data class SharedComplex(val id: String) : Marker

@RestController
@RequestMapping("/a")
class AController {

    data class ARequest(val name: String) : Marker

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
    sealed interface AResponse : Marker {
        data class Ok(val t: SharedComplex) : AResponse
        object Fail : AResponse
    }

    @PostMapping
    fun post(@RequestBody req: ARequest): AResponse = AResponse.Ok(SharedComplex(req.name))
}

@RestController
@RequestMapping("/b")
class BController {

    data class BRequest(val value: Int) : Marker

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
    sealed interface BResponse : Marker {
        data class Ok(val t: SharedComplex) : BResponse
        object Fail : BResponse
    }

    @PostMapping
    fun post(@RequestBody req: BRequest): BResponse = BResponse.Ok(SharedComplex(req.value.toString()))
}

class DeduplicatedTypesAcrossControllersTest {

    @Test
    fun typesAreRenderedOnlyOnceAcrossControllers() {
        val out = TypeScriptGenerator.build { cfg ->
            cfg.basePackages(javaClass.packageName)
                .includeApi {
                    listOf(
                        AController::class.java.name,
                        BController::class.java.name,
                    ).contains(it)
                }
        }.generate()

        val ts = out.tsApis()

        // Both controllers should be present
        assertTrue(ts.contains("export class AController"), "AController API not found in TS output")
        assertTrue(ts.contains("export class BController"), "BController API not found in TS output")

        // Ensure no type alias is emitted more than once
        val nameRegex = Regex("^type\\s+([A-Za-z0-9_]+)", RegexOption.MULTILINE)
        val names = nameRegex.findAll(ts).map { it.groupValues[1] }.toList()
        val dupes = names.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(dupes.isEmpty(), "Found duplicated type aliases: $dupes\n$ts")
    }
}
