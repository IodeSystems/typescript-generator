package com.iodesystems.ts

import com.iodesystems.ts.model.ApiModel
import com.iodesystems.ts.model.ExtractionResult
import com.iodesystems.ts.model.TsType
import java.io.File

// Output now behaves like a lightweight OutputContext. It stages routing helpers and
// metadata derived from Config and ExtractionResult, then write() performs the flush.
class Output(
    val extraction: ExtractionResult,
    val emitter: Emitter,
    val config: Config,
) {

    // --- Staging structures -------------------------------------------------
    private data class Staged(
        val outDir: File,
        val libFile: File?,
        val typesFile: File?,
        // For each API target file, the APIs and the typeSource mapping for that file
        val modules: List<StagedModule>,
    )

    private data class StagedModule(
        val targetFile: File,
        val apis: List<ApiModel>,
        val typeSource: Map<String, String>, // Map of Ts alias name -> module path (relative, no .ts)
        val libModule: String?, // Relative module path to lib (no .ts)
    )

    // Cached staging (built lazily and reused by write/ts helpers)
    private val staged: Staged = stage()

    private fun baseAlias(name: String): String =
        name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")

    private fun stage(): Staged {
        val outDir = File(config.outputDirectory)
        if (config.cleanOutputDir && outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()

        // Stage lib and types file targets, content written during write()
        val libTarget = config.emitLibFileName?.let { outDir.resolve(it) }
        val typesTarget = config.typesFileName?.let { outDir.resolve(it) }

        // Map of fully rendered TypeScript alias -> source .ts file (if types file is requested)
        val tsTypeNameToSourceFile: Map<String, File> =
            extraction.types.mapNotNull { type ->
                if (typesTarget == null) return@mapNotNull null
                type.typeScriptTypeName to typesTarget
            }.toMap()

        // Group APIs into output files per config rules
        val apisByFile: Map<String, List<ApiModel>> = extraction.apis.groupBy { api ->
            config.groupApiFile?.firstNotNullOfOrNull { (file, matches) ->
                if (matches.any { match -> api.jvmQualifiedClassName.matches(match.toRegex()) }) file else null
            } ?: "api.ts"
        }

        // Build StagedModule entries with per-file relative mappings
        val modules = apisByFile.map { (file, apis) ->
            val apiFile = File(outDir, file)
            val cache = mutableMapOf<File, String>()
            val relativeTypeSource: Map<String, String> = tsTypeNameToSourceFile.map { (tsTypeName, sourceFile) ->
                val modulePath = cache.getOrPut(sourceFile) {
                    "./" + apiFile.parentFile.toPath().relativize(sourceFile.toPath()).toString().removeSuffix(".ts")
                }
                // Key by base alias, not full rendered name, to match emitter lookups
                baseAlias(tsTypeName) to modulePath
            }.toMap()

            val libModule: String? = libTarget?.let { lf ->
                "./" + apiFile.parentFile.toPath().relativize(lf.toPath()).toString().removeSuffix(".ts")
            }

            StagedModule(apiFile, apis, relativeTypeSource, libModule)
        }.sortedBy { it.targetFile.name }

        return Staged(outDir, libTarget, typesTarget, modules)
    }

    // --- Compatibility helpers (produce strings without writing files) -----
    fun ts(): String {
        // Full output: lib + apis. Keep compatibility with previous single-file behavior
        val s = stage()
        val lib = emitter.emitLib()
        // Always provide a typeSource map; entries missing will be inlined by the emitter
        val apisCombined = s.modules.joinToString("\n\n") { m ->
            emitter.emitApis(m.apis, extraction, m.typeSource, m.libModule, skipLibDeclaration = (m.libModule != null))
        }
        return listOf(lib, apisCombined).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    // Library-only content
    fun tsLib(): String = emitter.emitLib()

    // For grouped output we will call emitTypes with a concrete list and mapping per-file.
    fun tsTypes(types: List<TsType>, typeSource: Map<String, String> = emptyMap()): String =
        emitter.emitTypes(types, typeSource)

    // API classes for selected controllers; types are inlined by default
    fun tsApis(apis: List<ApiModel>): String =
        emitter.emitApis(apis, extraction, skipLibDeclaration = true)

    // Backwards-compatible helper used by existing tests: all APIs
    fun tsApis(): String = emitter.emitApis(extraction.apis, extraction, skipLibDeclaration = true)

    // --- Flush to disk ------------------------------------------------------
    fun write() {
        val s = stage()

        // Write lib if requested
        s.libFile?.writeText(emitter.emitLib())

        // Write types file if requested (single file containing all types)
        s.typesFile?.writeText(emitter.emitTypes(extraction.types))

        // Write API modules
        s.modules.forEach { m ->
            // Always provide a typeSource; entries not present must be inlined by the emitter
            val content = emitter.emitApis(m.apis, extraction, m.typeSource, m.libModule)
            m.targetFile.parentFile?.mkdirs()
            m.targetFile.writeText(content)
        }
    }
}