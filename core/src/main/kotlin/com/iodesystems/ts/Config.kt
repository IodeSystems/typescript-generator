package com.iodesystems.ts

import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.extractor.SpringApiExtractor
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.reflect.KClass


data class Config(
    val cleanOutputDir: Boolean = false,
    val classPathUrls: List<String> = emptyList(),
    val mappedTypes: Map<String, String> = mapOf(
        OffsetDateTime::class.qualifiedName!! to "string",
        LocalDate::class.qualifiedName!! to "string",
        LocalTime::class.qualifiedName!! to "string"
    ),
    val outputDirectory: String = "./",
    val basePackages: List<String> = emptyList(),
    // Fully-qualified annotation class names that should mark a field/param/getter as optional
    // (in addition to library-specific or Kotlin-default rules). Defaults include common nullable
    // annotations for convenience in optional contexts (e.g., request params), and a placeholder
    // custom Optional.
    val optionalAnnotations: Set<String> = setOf(
        // Users can add their own via Builder.optionalAnnotations()/addOptionalAnnotations()
        // Common practice: treat nullable as optional for request params
        "javax.annotation.Nullable",
        "jakarta.annotation.Nullable",
        "org.jetbrains.annotations.Nullable",
    ),
    // Fully-qualified annotation class names that should mark a field/param/getter as nullable.
    // Defaults cover the common @Nullable variants.
    val nullableAnnotations: Set<String> = setOf(
        "javax.annotation.Nullable",
        "jakarta.annotation.Nullable",
        "org.jetbrains.annotations.Nullable",
    ),
    // Serializable include/exclude lists for API selection (regex strings). Empty = include all.
    val includeApiIncludes: List<String> = emptyList(),
    val includeApiExcludes: List<String> = emptyList(),
    // Regex-based type name replacements. Keys are regex, values are replacement (supports capture groups).
    // Applied to the simple class name to produce the TypeScript alias.
    val typeNameReplacements: Map<String, String> = mapOf(
        "[.$]" to ""
    ),
    // If set via emitLibAsSeparateFile(), callers may choose to write library helpers to a separate file.
    // Default null means do not imply separate output.
    val emitLibFileName: String? = null,
    // Group API controllers into files: map of output filename -> list of controller JVM FQNs.
    // When provided, we will emit one API file per entry, a lib file (api-lib.ts), and a shared types file.
    val groupApiFile: Map<String, List<String>>? = null,
    // Types file name used when groupApiFile is set (and in future when splitting types is enabled).
    val typesFileName: String? = null,
    // Map of external TypeScript type simple name -> full import line to emit as-is
    // Example: "Dayjs" -> "import {Dayjs} from 'dayjs'"
    val externalImportLines: Map<String, String> = emptyMap(),
) {

    companion object {
        fun build(fn: Builder.() -> Builder): Config {
            return Config().build(fn)
        }
    }

    fun build(fn: Builder.() -> Builder): Config {
        return Builder(this).fn().config
    }

    private val regexCache = mutableMapOf<String, Regex>()

    fun includeApi(name: String): Boolean {
        val included =
            if (includeApiIncludes.isEmpty()) true
            else includeApiIncludes.any {
                name == it || regexCache.getOrPut(it) { Regex(it) }.containsMatchIn(name)
            }
        if (!included) return false
        val excluded =
            if (includeApiExcludes.isEmpty()) false
            else includeApiExcludes.any {
                name == it || regexCache.getOrPut(it) { Regex(it) }.containsMatchIn(name)
            }
        return !excluded
    }

    fun customNaming(cls: String): String {
        var out = cls
        for ((pattern, repl) in typeNameReplacements) {
            out = out.replace(Regex(pattern), repl)
        }
        return out
    }

    fun jvmExtractor() = JvmExtractor(this)
    fun apiExtractor() = SpringApiExtractor(this)

    data class Builder(
        val config: Config = Config(),
    ) {
        fun cleanOutputDir(set: Boolean = true) = copy(config = config.run {
            copy(cleanOutputDir = set)
        })

        fun outputDirectory(dir: String) = copy(config = config.run {
            copy(outputDirectory = dir)
        })

        fun mappedType(klass: KClass<*>, tsIdentifier: String) = copy(config = config.run {
            copy(mappedTypes = mappedTypes + (klass.java.name to tsIdentifier))
        })

        fun basePackages(vararg pkgs: String) = copy(config = config.run {
            copy(basePackages = pkgs.toList())
        })

        inline fun <reified T> includeApi() = includeApi(T::class)
        fun includeApi(vararg kClass: KClass<*>) = copy(config = config.run {
            copy(includeApiIncludes = includeApiIncludes + kClass.map { it.java.name })
        })

        fun includeApis(vararg patterns: String) = copy(config = config.run {
            copy(includeApiIncludes = patterns.toList())
        })

        fun excludeApis(vararg patterns: String) = copy(config = config.run {
            copy(includeApiExcludes = patterns.toList())
        })

        fun optionalAnnotations(vararg fqns: String) = copy(config = config.run {
            copy(optionalAnnotations = fqns.toSet())
        })

        fun addOptionalAnnotations(vararg fqns: String) = copy(config = config.run {
            copy(optionalAnnotations = optionalAnnotations + fqns)
        })

        fun nullableAnnotations(vararg fqns: String) = copy(config = config.run {
            copy(nullableAnnotations = fqns.toSet())
        })

        fun addNullableAnnotations(vararg fqns: String) = copy(config = config.run {
            copy(nullableAnnotations = nullableAnnotations + fqns)
        })

        // Enable separate library output file name (default "api-lib.ts")
        fun emitLibAsSeparateFile(name: String = "api-lib.ts") =
            copy(config = config.run { copy(emitLibFileName = name) })

        fun groupApis(grouping: Map<String, List<String>>) = copy(config = config.run {
            copy(groupApiFile = grouping)
        })

        fun typesFileName(name: String) = copy(config = config.run { copy(typesFileName = name) })

        // Enable emitting a separate types file. Optionally override the file name.
        fun emitTypesAsSeparateFile(name: String = "api-types.ts") = copy(config = config.run {
            copy(typesFileName = name)
        })

        fun typeNameReplacements(mapping: Map<String, String>) = copy(config = config.run {
            copy(typeNameReplacements = mapping)
        })

        fun addTypeNameReplacement(pattern: String, replacement: String) = copy(config = config.run {
            copy(typeNameReplacements = typeNameReplacements + (pattern to replacement))
        })

        fun externalImportLines(mapping: Map<String, String>) = copy(config = config.run {
            copy(externalImportLines = mapping)
        })

        fun addExternalImportLine(name: String, importLine: String) = copy(config = config.run {
            copy(externalImportLines = externalImportLines + (name to importLine))
        })
    }

}
