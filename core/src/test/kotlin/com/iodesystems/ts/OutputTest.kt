package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.web.bind.annotation.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.Ignore
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
    @Ignore
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
        assertTrue(unified.contains("export type ApiOptions"), "api.ts should include lib helpers when not split")
        assertTrue(unified.contains("class OutputApiA"), "api.ts should contain OutputApiA")
        assertTrue(unified.contains("class OutputApiB"), "api.ts should contain OutputApiB")
        assertTrue(unified.contains("class OutputApiC"), "api.ts should contain OutputApiC")
        assertTrue(
            unified.contains("export type OutputApiDUnionTesting = OutputApiDUnionTestingOk | OutputApiDUnionTestingEr"),
            "api.ts should render unions correctly, did not see expected `export type OutputApiDUnionTesting = OutputApiDUnionTestingOk | OutputApiDUnionTestingEr`"
        )
        assertTrue(
            unified.contains("Promise<OutputApiDUnionTesting>"),
            "api.ts should not render type parameters on return type unions"
        )
        // External import line should be present
        assertTrue(unified.contains("import type {Dayjs} from 'dayjs'"), "api.ts should include external import lines")

        assertTrue(unified.contains("type OutputApiDUnionTesting ="), "api.ts should include typescript union parents")
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
        assertTrue(sepLibTxt.contains("export type ApiOptions"), "api-lib.ts should contain library helpers")
        assertTrue(
            sepApiTxt.contains("import { ApiOptions, fetchInternal, flattenQueryParams } from './api-lib'"),
            "api.ts should import from ./api-lib"
        )
        assertTrue(sepApiTxt.contains("class OutputApiA"), "api.ts should contain APIs")
        // External import line should still be present in API file
        assertTrue(
            sepApiTxt.contains("import type {Dayjs} from 'dayjs'"),
            "api.ts should include external import lines"
        )
    }

    @Test
    @Ignore
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
        }.generate().write()

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
        assertTrue(
            aTxt.contains("import type {Dayjs} from 'dayjs'"),
            "a.ts should include external import lines when referenced"
        )
        assertTrue(
            bTxt.contains("import type {Dayjs} from 'dayjs'"),
            "b.ts should include external import lines when referenced"
        )
        assertTrue(
            rTxt.contains("import type {Dayjs} from 'dayjs'"),
            "rest.ts should include external import lines when referenced"
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
    @Ignore
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
        assertTrue(sTypesTxt.contains("export type Person"), "api-types.ts should contain exported Person type")
        kotlin.test.assertFalse(
            sApiTxt.contains("export type Person"),
            "api.ts should not declare Person type when split is enabled"
        )
        assertTrue(
            sApiTxt.contains("import type { Person, OutputApiDUnionTesting } from './api-types'"),
            "api.ts should import Person and OutputApiDUnionTesting from ./api-types"
        )
    }
}