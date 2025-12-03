package com.iodesystems.ts

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertTrue

@RestController
@RequestMapping("/a")
class OutputApiA {

    @PostMapping
    fun run(@RequestBody req: OffsetDateTime): Boolean = true
}

@RestController
@RequestMapping("/b")
class OutputApiB {
    @PostMapping
    fun run(@RequestBody req: OffsetDateTime): Boolean = true
}

@RestController
@RequestMapping("/c")
class OutputApiC {
    @PostMapping
    fun run(@RequestBody req: OffsetDateTime): Boolean = true
}

class OutputTest {
    @Test
    fun outputTest() {
        var config: Config? = null
        TypeScriptGenerator.build { b ->
            b
                .includeApi { name -> name.contains("OutputApi") }
                .outputDirectory("./build/test/output-test-unified")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import Dayjs from 'dayjs'"))
                .also { config = it.config }
        }
            .generate().write()
        // Verify unified output structure
        val unifiedDir = File("./build/test/output-test-unified")
        val unifiedApi = File(unifiedDir, "api.ts")
        assertTrue(unifiedApi.exists(), "Unified mode should generate api.ts")
        val unified = unifiedApi.readText()
        // Should include lib helpers inline and API classes
        assertTrue(unified.contains("export type ApiOptions"), "api.ts should include lib helpers when not split")
        assertTrue(unified.contains("class OutputApiA"), "api.ts should contain OutputApiA")
        assertTrue(unified.contains("class OutputApiB"), "api.ts should contain OutputApiB")
        assertTrue(unified.contains("class OutputApiC"), "api.ts should contain OutputApiC")
        // External import line should be present
        assertTrue(unified.contains("import Dayjs from 'dayjs'"), "api.ts should include external import lines")

        // Carry forward config
        config!!
        TypeScriptGenerator(
            Config.Builder(config)
                .emitLibAsSeparateFile()
                .outputDirectory("./build/test/output-separate-lib")
                .config
        ).generate().write()

        // Verify separate lib output structure
        val sepDir = File("./build/test/output-separate-lib")
        val sepLib = File(sepDir, "api-lib.ts")
        val sepApi = File(sepDir, "api.ts")
        assertTrue(sepLib.exists(), "Separate-lib mode should generate api-lib.ts")
        assertTrue(sepApi.exists(), "Separate-lib mode should generate api.ts")
        val sepLibTxt = sepLib.readText()
        val sepApiTxt = sepApi.readText()
        assertTrue(sepLibTxt.contains("export type ApiOptions"), "api-lib.ts should contain library helpers")
        assertTrue(sepApiTxt.contains("import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'"), "api.ts should import from ./api-lib")
        assertTrue(sepApiTxt.contains("class OutputApiA"), "api.ts should contain APIs")
        // External import line should still be present in API file
        assertTrue(sepApiTxt.contains("import Dayjs from 'dayjs'"), "api.ts should include external import lines")

        TypeScriptGenerator(
            Config.Builder(config)
                .emitLibAsSeparateFile()
                .groupApis(
                    mapOf(
                        "a.ts" to listOf("com\\.iodesystems\\.ts\\.OutputApiA"),
                        "b.ts" to listOf(".*B$"),
                        "rest.ts" to listOf(".*C$")
                    )
                )
                .outputDirectory("./build/test/output-api-groups")
                .config
        ).generate().write()

        // Verify grouped outputs structure and contents
        val gDir = File("./build/test/output-api-groups")
        val gLib = File(gDir, "api-lib.ts")
        val gTypes = File(gDir, "api-types.ts")
        val gA = File(gDir, "a.ts")
        val gB = File(gDir, "b.ts")
        val gRest = File(gDir, "rest.ts")
        assertTrue(gLib.exists(), "Grouped mode should generate api-lib.ts")
        assertTrue(gTypes.exists(), "Grouped mode should generate api-types.ts")
        assertTrue(gA.exists(), "Grouped mode should generate a.ts")
        assertTrue(gB.exists(), "Grouped mode should generate b.ts")
        assertTrue(gRest.exists(), "Grouped mode should generate rest.ts")

        val aTxt = gA.readText()
        val bTxt = gB.readText()
        val rTxt = gRest.readText()
        // Each group file should import helpers from lib
        assertTrue(aTxt.contains("from './api-lib'"), "a.ts should import from api-lib")
        assertTrue(bTxt.contains("from './api-lib'"), "b.ts should import from api-lib")
        assertTrue(rTxt.contains("from './api-lib'"), "rest.ts should import from api-lib")
        // API classes segregated
        assertTrue(aTxt.contains("class OutputApiA"), "a.ts should contain OutputApiA only")
        kotlin.test.assertFalse(aTxt.contains("OutputApiB"), "a.ts should not contain OutputApiB")
        kotlin.test.assertFalse(aTxt.contains("OutputApiC"), "a.ts should not contain OutputApiC")
        assertTrue(bTxt.contains("class OutputApiB"), "b.ts should contain OutputApiB only")
        kotlin.test.assertFalse(bTxt.contains("OutputApiA"), "b.ts should not contain OutputApiA")
        kotlin.test.assertFalse(bTxt.contains("OutputApiC"), "b.ts should not contain OutputApiC")
        assertTrue(rTxt.contains("class OutputApiC"), "rest.ts should contain OutputApiC only")
        kotlin.test.assertFalse(rTxt.contains("OutputApiA"), "rest.ts should not contain OutputApiA")
        kotlin.test.assertFalse(rTxt.contains("OutputApiB"), "rest.ts should not contain OutputApiB")

        // External import lines should appear in group api files where types are referenced
        assertTrue(aTxt.contains("import Dayjs from 'dayjs'"), "a.ts should include external import lines when referenced")
        assertTrue(bTxt.contains("import Dayjs from 'dayjs'"), "b.ts should include external import lines when referenced")
        assertTrue(rTxt.contains("import Dayjs from 'dayjs'"), "rest.ts should include external import lines when referenced")
    }
}