package com.iodesystems.ts

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.test.Test

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
        config!!
        TypeScriptGenerator(
            Config.Builder(config)
                .emitLibAsSeparateFile()
                .outputDirectory("./build/test/output-separate-lib")
                .config
        ).generate().write()

        TypeScriptGenerator(
            Config.Builder(config)
                .emitLibAsSeparateFile()
                .groupApis(
                    mapOf(
                        "a.ts" to listOf("OutputApiA"),
                        "b.ts" to listOf("B$"),
                        "rest.ts" to listOf(".*")
                    )
                )
                .outputDirectory("./build/test/output-api-groups")
                .config
        ).generate().write()

    }
}