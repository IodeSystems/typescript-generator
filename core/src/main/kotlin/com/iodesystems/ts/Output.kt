package com.iodesystems.ts

import com.iodesystems.ts.model.ExtractionResult
import com.iodesystems.ts.model.TsBody
import com.iodesystems.ts.model.TsType
import java.io.File

// Output container that holds the scan, emitter, and config, and can generate/write artifacts on demand
class Output(
    val extraction: ExtractionResult,
    val emitter: Emitter,
    val config: Config,
) {
    fun ts(): String {
        // Full output: lib + apis. Keep compatibility with previous single-file behavior
        val lib = emitter.emitLib()
        val apis = emitter.emitApis(extraction.apis, extraction, null)
        val parts = listOf(lib, apis).filter { it.isNotBlank() }
        return parts.joinToString("\n\n")
    }

    // Library-only content
    fun tsLib(): String = emitter.emitLib()

    // For grouped output we will call emitTypes with a concrete list and mapping per-file.
    fun tsTypes(types: List<com.iodesystems.ts.model.TsType>, typeSource: Map<String, String>? = null): String =
        emitter.emitTypes(types, typeSource)

    // API classes for selected controllers; types are inlined by default (by passing null typeSource)
    fun tsApis(apis: List<com.iodesystems.ts.model.ApiModel>): String =
        emitter.emitApis(apis, extraction, null)

    // Backwards-compatible helper used by existing tests: all APIs
    fun tsApis(): String = emitter.emitApis(extraction.apis, extraction, null)

    fun write() {
        val outDir = File(config.outputDirectory)
        outDir.mkdirs()

        val group = config.groupApiFile

        if (group == null || group.isEmpty()) {
            // Simple mode: single api.ts. If lib split is requested, write lib separately and import it.
            val apiFile = File(outDir, "api.ts")
            val libFileName = config.emitLibFileName
            if (libFileName == null) {
                // Single file: lib + apis (inline types)
                apiFile.writeText(ts())
            } else {
                // Write lib file
                File(outDir, libFileName).writeText(tsLib())
                // Write api file that imports lib and contains apis with inline types
                val importPath = libFileName.removeSuffix(".ts")
                val header = "import { ApiOptions, fetchInternal, flattenQueryParams } from './$importPath'\n\n"
                val body = emitter.emitApis(extraction.apis, extraction, null)
                apiFile.writeText(header + body)
            }
            return
        }

        // Grouped mode: lib file, shared types file, and per-group API files.
        val libFileName = config.emitLibFileName ?: "api-lib.ts"
        val typesFileName = config.typesFileName

        // Write lib file
        File(outDir, libFileName).writeText(tsLib())

        // Prepare import paths
        val libImportPath = libFileName.removeSuffix(".ts")
        val typesImportPath = typesFileName.removeSuffix(".ts")

        // Write types file: export all types (single module), with no cross-file imports (typeSource provided is for API files)
        File(outDir, typesFileName).writeText(tsTypes(extraction.types, null))

        // Circular dependency detection: API files only depend on lib and types (and not on each other),
        // and lib/types do not depend on API files, so cycles are not expected here. Guard anyway for self-cycles.
        if (libFileName == typesFileName) {
            throw IllegalStateException("Library and types file names must differ to avoid circular dependency: $libFileName")
        }

        // Helper to collect type aliases referenced by a set of APIs
        fun collectUsedTypeAliases(controllerFqns: List<String>): Set<String> {
            val typesByAlias = extraction.types.associateBy { it.typeScriptTypeName.substringBefore(" & ").substringBefore(" | ").substringBefore("<") }
            val apis = extraction.apis.filter { controllerFqns.contains(it.jvmQualifiedClassName) && config.includeApi(it.jvmQualifiedClassName) }
            val out = linkedSetOf<String>()
            fun baseAlias(name: String): String = name.substringBefore(" & ").substringBefore(" | ").substringBefore("<")
            fun walk(t: TsType?) {
                when (val b = t?.body) {
                    is TsBody.PrimitiveBody -> {
                        val alias = baseAlias(b.tsName)
                        if (typesByAlias.containsKey(alias)) out += alias
                    }
                    is TsBody.ArrayBody -> walk(b.element)
                    is TsBody.ObjectBody -> {
                        val alias = baseAlias(t.typeScriptTypeName)
                        if (typesByAlias.containsKey(alias)) out += alias
                        b.tsFields.forEach { f -> walk(f.type) }
                    }
                    is TsBody.UnionBody -> {
                        b.options.forEach { opt ->
                            val alias = baseAlias(opt.typeScriptTypeName)
                            if (typesByAlias.containsKey(alias)) out += alias
                            walk(opt)
                        }
                    }
                    null -> {}
                }
            }
            apis.forEach { api ->
                api.apiMethods.forEach { m ->
                    walk(m.requestBodyType)
                    walk(m.queryParamsType)
                    m.pathTsFields.values.forEach { f -> walk(f.type) }
                    walk(m.responseBodyType)
                }
            }
            return out
        }

        // Emit per-group API files: emitter handles type imports from typeSource
        group.forEach { (fileName, controllerFqns) ->
            val selectedApis = extraction.apis.filter { controllerFqns.contains(it.jvmQualifiedClassName) && config.includeApi(it.jvmQualifiedClassName) }
            val used = collectUsedTypeAliases(controllerFqns)
            // Build a narrow typeSource for this file (only the used aliases), all pointing to the shared types module
            val localTypeSource = used.associateWith { typesImportPath }
            val body = emitter.emitApis(selectedApis, extraction, localTypeSource)
            // Naive circular check: if a group writes into the same file as lib or types, error
            if (fileName == libFileName || fileName == typesFileName) {
                throw IllegalStateException("API group output file conflicts with lib/types file: $fileName")
            }
            File(outDir, fileName).writeText(body)
        }
    }
}