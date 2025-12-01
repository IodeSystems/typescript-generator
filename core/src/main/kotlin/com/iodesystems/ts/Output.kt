package com.iodesystems.ts

import com.iodesystems.ts.model.ExtractionResult
import java.io.File

// Output container that holds the scan, emitter, and config, and can generate/write artifacts on demand
class Output(
    val extraction: ExtractionResult,
    val emitter: Emitter,
    val config: Config,
) {
    fun ts(): String {
        // Full output: lib + apis + types grouped (if configured). For now, keep compatibility with previous tests
        val lib = emitter.emitLib()
        val inlineTypes = config.emitTypesFileNameMap == null
        val apis = emitter.emitApis(extraction, inlineTypes = inlineTypes)
        val parts = listOf(lib, apis).filter { it.isNotBlank() }
        return parts.joinToString("\n\n")
    }

    // Library-only content
    fun tsLib(): String = emitter.emitLib()

    // Type content grouped by filename; by default include all
    fun tsTypes(typeFileInclude: (String) -> Boolean = { true }): Map<String, String> = emptyMap()

    // API classes for selected controllers; if separate types not configured, inline types per API
    fun tsApis(match: (String) -> Boolean = { true }): String {
        val inlineTypes = config.emitTypesFileNameMap == null
        return emitter.emitApis(extraction, match, inlineTypes)
    }

    fun write(fileName: String = "api.ts"): File {
        val dir = File(config.outputDirectory)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(ts())
        return file
    }
}