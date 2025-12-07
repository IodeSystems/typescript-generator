package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import org.springframework.web.bind.annotation.*
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
    fun run(@RequestBody req: OffsetDateTime): Person = error("not implemented")
}

@RestController
@RequestMapping("/c")
class OutputApiC {
    @PostMapping
    fun run(@RequestBody req: OffsetDateTime): Boolean = true
}

data class Person(
    val name: String,
    val age: Int,
    val at: OffsetDateTime? = null
)


@RestController
@RequestMapping("/d")
class OutputApiD {
    @PostMapping
    fun run(@RequestBody req: Person): Person = req

    @GetMapping
    fun get(): UnionTesting = error("not implemented")

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
    sealed interface UnionTesting {
        data object Ok : UnionTesting
        data object Er : UnionTesting
    }
}

class OutputTest {
    @Test
    fun unifiedOutputTest() {
        TypeScriptGenerator.build { b ->
            b
                .cleanOutputDir()
                .includeApis(".*OutputApi.*")
                .outputDirectory("./build/test/output-test/test-unified")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import type {Dayjs} from 'dayjs'"))
        }
            .generate().write()

        // Verify unified output structure
        val unifiedDir = File("./build/test/output-test/test-unified")
        val unifiedApi = File(unifiedDir, "api.ts")
        assertTrue(unifiedApi.exists(), "Unified mode should generate api.ts")
        val unified = unifiedApi.readText()
        // Should include lib helpers inline and API classes
        unified.assertContains(
            fragment = "export type ApiOptions",
            why = "api.ts should include lib helpers when not split"
        )
        unified.assertContains(
            fragment = "class OutputApiA",
            why = "api.ts should contain OutputApiA"
        )
        unified.assertContains(
            fragment = "class OutputApiB",
            why = "api.ts should contain OutputApiB"
        )
        unified.assertContains(
            fragment = "class OutputApiC",
            why = "api.ts should contain OutputApiC"
        )

        unified.assertContains(
            fragment = "export type OutputApiDUnionTestingUnion = OutputApiDUnionTesting & (OutputApiDUnionTestingEr | OutputApiDUnionTestingOk)",
            why = "api.ts should render unions correctly"
        )

        unified.assertContains(
            fragment = "Promise<OutputApiDUnionTestingUnion>",
            why = "api.ts should not render type parameters on return type unions"
        )
        // External import line should be present
        unified.assertContains(
            fragment = "import type {Dayjs} from 'dayjs'",
            why = "api.ts should include external import lines"
        )

        unified.assertContains(
            fragment = "type OutputApiDUnionTestingUnion =",
            why = "api.ts should include typescript union parents"
        )
    }

    @Test
    fun separateLibOutputTest() {
        // Generate with separate lib file
        TypeScriptGenerator.build {
            it
                .cleanOutputDir()
                .includeApis(".*OutputApi.*")
                .emitLibAsSeparateFile()
                .outputDirectory("./build/test/output-test/separate-lib")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import type {Dayjs} from 'dayjs'"))
        }.generate().write()

        // Verify separate lib output structure
        val sepDir = File("./build/test/output-test/separate-lib")
        val sepLib = File(sepDir, "api-lib.ts")
        val sepApi = File(sepDir, "api.ts")
        assertTrue(sepLib.exists(), "Separate-lib mode should generate api-lib.ts")
        assertTrue(sepApi.exists(), "Separate-lib mode should generate api.ts")
        val sepLibTxt = sepLib.readText()
        val sepApiTxt = sepApi.readText()
        sepLibTxt.assertContains(
            fragment = "export type ApiOptions",
            why = "api-lib.ts should contain library helpers"
        )
        sepApiTxt.assertContains(
            fragment = "import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'",
            why = "api.ts should import from ./api-lib"
        )
        sepApiTxt.assertContains(
            fragment = "class OutputApiA",
            why = "api.ts should contain APIs"
        )
        sepApiTxt.assertContains(
            fragment = "import type {Dayjs} from 'dayjs'",
            why = "api.ts should include typescript union parents"
        )
    }

    @Test
    fun groupedOutputTest() {
        // Generate grouped APIs
        TypeScriptGenerator.build {
            it
                .cleanOutputDir()
                .includeApis(".*OutputApi.*")
                .emitLibAsSeparateFile()
                .groupApis(
                    mapOf(
                        "a.ts" to listOf("com\\.iodesystems\\.ts\\.OutputApiA"),
                        "b.ts" to listOf(".*B$"),
                        "rest.ts" to listOf(".*C$")
                    )
                )
                .outputDirectory("./build/test/output-test/api-groups")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import type {Dayjs} from 'dayjs'"))
        }.generate()
            .write()

        // Verify grouped outputs structure and contents
        val gDir = File("./build/test/output-test/api-groups")
        val gLib = File(gDir, "api-lib.ts")
        val catchall = File(gDir, "api.ts")
        val gA = File(gDir, "a.ts")
        val gB = File(gDir, "b.ts")
        val gRest = File(gDir, "rest.ts")
        assertTrue(gLib.exists(), "Grouped mode should generate api-lib.ts")
        assertTrue(gA.exists(), "Grouped mode should generate a.ts")
        assertTrue(gB.exists(), "Grouped mode should generate b.ts")
        assertTrue(gRest.exists(), "Grouped mode should generate rest.ts")
        assertTrue(catchall.exists(), "Non-caught apis go into default api.ts")

        val aTxt = gA.readText()
        val bTxt = gB.readText()
        val rTxt = gRest.readText()
        // Each group file should import helpers from lib
        aTxt.assertContains(fragment = "from './api-lib'", why = "a.ts should import from api-lib")
        bTxt.assertContains(fragment = "from './api-lib'", why = "b.ts should import from api-lib")
        rTxt.assertContains(fragment = "from './api-lib'", why = "rest.ts should import from api-lib")
        // API classes segregated
        aTxt.assertContains(fragment = "class OutputApiA", why = "a.ts should contain OutputApiA only")
        kotlin.test.assertFalse(aTxt.contains("OutputApiB"), "a.ts should not contain OutputApiB")
        kotlin.test.assertFalse(aTxt.contains("OutputApiC"), "a.ts should not contain OutputApiC")
        bTxt.assertContains(fragment = "class OutputApiB", why = "b.ts should contain OutputApiB only")
        kotlin.test.assertFalse(bTxt.contains("OutputApiA"), "b.ts should not contain OutputApiA")
        kotlin.test.assertFalse(bTxt.contains("OutputApiC"), "b.ts should not contain OutputApiC")
        rTxt.assertContains(fragment = "class OutputApiC", why = "rest.ts should contain OutputApiC only")
        kotlin.test.assertFalse(rTxt.contains("OutputApiA"), "rest.ts should not contain OutputApiA")
        kotlin.test.assertFalse(rTxt.contains("OutputApiB"), "rest.ts should not contain OutputApiB")

        // External import lines should appear in group api files where types are referenced
        aTxt.assertContains(
            fragment = "import type {Dayjs} from 'dayjs'",
            why = "a.ts should include external import lines when referenced"
        )
        bTxt.assertContains(
            fragment = "import type {Dayjs} from 'dayjs'",
            why = "b.ts should include external import lines when referenced"
        )
        rTxt.assertContains(
            fragment = "import type {Dayjs} from 'dayjs'",
            why = "rest.ts should include external import lines when referenced"
        )
    }


    @Test
    fun groupedOutputWithSplitTypesTest() {
        // Generate grouped APIs
        TypeScriptGenerator.build {
            it.cleanOutputDir()
                .includeApis(".*OutputApi.*")
                .typesFileName("api-types.ts")
                .emitLibAsSeparateFile()
                .groupApis(
                    mapOf(
                        "a.ts" to listOf("com\\.iodesystems\\.ts\\.OutputApiA"),
                        "b.ts" to listOf(".*B$"),
                        "rest.ts" to listOf(".*C$")
                    )
                )
                .outputDirectory("./build/test/output-test/api-groups-split-types")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import type {Dayjs} from 'dayjs'"))
        }.generate().write()

    }

    @Test
    fun splitTypesSimpleOutputTest() {
        // Simple mode with split types enabled (no groups)
        TypeScriptGenerator.build { b ->
            b
                .cleanOutputDir()
                .emitLibAsSeparateFile()
                .typesFileName("api-types.ts")
                .includeApi<OutputApiD>()
                .outputDirectory("./build/test/output-test/split-types-simple")
                .mappedType(OffsetDateTime::class, "Dayjs | string")
                .mappedType(LocalDate::class, "string")
                .mappedType(LocalTime::class, "string")
                .externalImportLines(mapOf("Dayjs" to "import type {Dayjs} from 'dayjs'"))
        }.generate().write()

        val sDir = File("./build/test/output-test/split-types-simple")
        val sLib = File(sDir, "api-lib.ts")
        val sTypes = File(sDir, "api-types.ts")
        val sApi = File(sDir, "api.ts")
        assertTrue(sLib.exists(), "Split-types simple mode should generate api-lib.ts")
        assertTrue(sTypes.exists(), "Split-types simple mode should generate api-types.ts")
        assertTrue(sApi.exists(), "Split-types simple mode should generate api.ts")
        val sTypesTxt = sTypes.readText()
        val sApiTxt = sApi.readText()
        sTypesTxt.assertContains(
            fragment = "export type Person",
            why = "api-types.ts should contain exported Person type"
        )
        kotlin.test.assertFalse(
            sApiTxt.contains("export type Person"),
            "api.ts should not declare Person type when split is enabled"
        )
        sApiTxt.assertContains(
            fragment = "import { OutputApiDUnionTestingUnion, Person } from './api-types'\n",
            why = "api.ts should import needed types via a single barrelized named import"
        )


    }
}