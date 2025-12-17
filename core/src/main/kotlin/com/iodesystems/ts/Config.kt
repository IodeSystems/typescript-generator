package com.iodesystems.ts

import com.iodesystems.ts.adapter.JacksonJsonAdapter
import com.iodesystems.ts.adapter.SpringApiAdapter
import com.iodesystems.ts.extractor.ApiExtractor
import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.extractor.SpringApiExtractor
import io.github.classgraph.ScanResult
import java.io.Serializable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.reflect.KClass

data class Config(
    val includeRefComments: Boolean = true,
    val cleanOutputDir: Boolean = false,
    val classPathUrls: List<String> = emptyList(),
    val setsAsArrays: Boolean = true,
    val omitTypes: List<String> = listOf(
        Class::class,
        java.lang.Comparable::class,
        java.io.Serializable::class,
        kotlin.enums.EnumEntries::class,
        java.lang.Enum::class
    ).map { it.qualifiedName!! } +
            // Kotlin rewrites java.lang.Comparable to kotlin.lang.Comparable
            listOf(
                "java.lang.Comparable",
                "java.lang.Enum"
            ),
    val mappedTypes: Map<String, String> = mapOf(
        // Using JavaTimeModule from jackson-datatype-jsr310
        Duration::class.qualifiedName!! to "string",
        OffsetDateTime::class.qualifiedName!! to "string",
        LocalDate::class.qualifiedName!! to "string",
        LocalTime::class.qualifiedName!! to "string",
        // Optional
        java.util.Optional::class.qualifiedName!! to "T | null"
    ),
    val outputDirectory: String = "./",
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
    // Package accept/reject patterns for ClassGraph scanning and API selection.
    // Supports package prefixes (e.g., "com.example") or regex patterns.
    val packageAccept: List<String> = emptyList(),
    val packageReject: List<String> = emptyList(),
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
    // Lines to write at the top of every generated TS file (in order). Useful for lint directives.
    val headerLines: List<String> = emptyList(),
    // Enables extra diagnostic logs from heavy phases (type registration, queue sizes, memory snapshots).
    // Keep default false to avoid overhead in normal runs.
    val diagnosticLogging: Boolean = false,
    // Jackson naming behavior options (mirrors MapperFeature settings)
    // AUTO_DETECT_IS_GETTERS: When true, boolean isX() getters are detected and "is" prefix is stripped.
    // E.g., isActive() -> "active" in JSON. Default true (matches Jackson default).
    val autoDetectIsGetters: Boolean = true,
    // ALLOW_IS_GETTERS_FOR_NON_BOOLEAN: When true, isX() methods returning non-boolean types also have
    // "is" prefix stripped. E.g., isOptional(): Optional<Boolean> -> "optional" in JSON.
    // Default true (matches Jackson 2.14+ default, added for Kotlin support).
    val allowIsGettersForNonBoolean: Boolean = true,
    // USE_STD_BEAN_NAMING: When true, use strict JavaBeans naming rules where leading uppercase is
    // preserved unless followed by another lowercase letter. E.g., getURL() -> "URL" (not "url").
    // Default false (matches Jackson default which always lowercases: getURL() -> "url").
    val useStdBeanNaming: Boolean = false,
) : Serializable {

    override fun toString(): String {
        return buildString {
            append("Config(")
            val props = listOfNotNull(
                if (cleanOutputDir) "cleanOutputDir=$cleanOutputDir" else null,
                if (classPathUrls.isNotEmpty()) "classPathUrls=$classPathUrls" else null,
                if (mappedTypes.isNotEmpty()) "mappedTypes=$mappedTypes" else null,
                if (outputDirectory != "./") "outputDirectory='$outputDirectory'" else null,
                if (optionalAnnotations.isNotEmpty()) "optionalAnnotations=$optionalAnnotations" else null,
                if (nullableAnnotations.isNotEmpty()) "nullableAnnotations=$nullableAnnotations" else null,
                if (packageAccept.isNotEmpty()) "packageAccept=$packageAccept" else null,
                if (packageReject.isNotEmpty()) "packageReject=$packageReject" else null,
                if (typeNameReplacements.isNotEmpty()) "typeNameReplacements=$typeNameReplacements" else null,
                emitLibFileName?.let { "emitLibFileName='$it'" },
                groupApiFile?.let { "groupApiFile=$it" },
                typesFileName?.let { "typesFileName='$it'" },
                if (externalImportLines.isNotEmpty()) "externalImportLines=$externalImportLines" else null,
                if (headerLines.isNotEmpty()) "headerLines=$headerLines" else null,
                if (diagnosticLogging) "diagnosticLogging=$diagnosticLogging" else null
            )
            append(props.joinToString(", "))
            append(")")
        }
    }

    companion object {
        /**
         * Build a [Config] using the mutable [Builder] with a chain-style lambda that returns the builder.
         */
        fun build(fn: Builder.() -> Unit): Config {
            return Config().build(fn)
        }
    }

    /**
     * Build a copy of this [Config] using the mutable [Builder] with chain-style lambda.
     */
    fun build(fn: Builder.() -> Unit): Config {
        val builder = Builder(this)
        builder.fn()
        return builder.config
    }

    private val regexCache = mutableMapOf<String, Regex>()

    fun includeType(fqcn: String): Boolean {
        return !omitTypes.any { fqcn.startsWith(it) }
    }

    fun includeApi(name: String): Boolean {
        val included =
            if (packageAccept.isEmpty()) true
            else packageAccept.any {
                name == it || name.startsWith("$it.") || regexCache.getOrPut(it) { Regex(it) }.containsMatchIn(name)
            }
        if (!included) return false
        val excluded =
            if (packageReject.isEmpty()) false
            else packageReject.any {
                name == it || name.startsWith("$it.") || regexCache.getOrPut(it) { Regex(it) }.containsMatchIn(name)
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


    fun jvmExtractor(scan: ScanResult) = JvmExtractor(
        config = this,
        jsonAdapter = JacksonJsonAdapter(),
        apiAdapter = SpringApiAdapter(this),
        scan = scan
    )

    fun apiExtractor(): ApiExtractor = SpringApiExtractor(this)

    /**
     * Mutable builder for [Config]. Methods mutate internal [config] and return this builder, so you can
     * either chain calls or call them as separate statements.
     */
    class Builder(
        var config: Config = Config(),
    ) {
        /** Replace the list of FQCN prefixes to omit entirely from type emission. */
        fun omitTypes(vararg fqns: String): Builder {
            config = config.copy(omitTypes = fqns.toList()); return this
        }

        /** Treat Kotlin/Java `Set` like an array in TypeScript (default true). */
        fun setsAsArrays(set: Boolean = true): Builder {
            config = config.copy(setsAsArrays = set); return this
        }

        /** Emit `// ref:` comments alongside TS types to show original JVM names. */
        fun includeRefComments(set: Boolean = true): Builder {
            config = config.copy(includeRefComments = set); return this
        }

        /** Update classPath urls using a transformation function. Used by the Gradle plugin. */
        fun classPathUrls(f: (List<String>) -> List<String>): Builder {
            val newUrls = f(config.classPathUrls)
            config = config.copy(classPathUrls = newUrls)
            return this
        }

        /** Clean output directory before writing files. */
        fun cleanOutputDir(set: Boolean = true): Builder {
            config = config.copy(cleanOutputDir = set); return this
        }

        /** Destination directory for generated files. */
        fun outputDirectory(dir: String): Builder {
            config = config.copy(outputDirectory = dir); return this
        }

        /** Add/override multiple JVM→TS type mappings. */
        fun mappedTypes(map: Map<String, String>): Builder {
            config = config.copy(mappedTypes = config.mappedTypes + map); return this
        }

        /** Map a single JVM class to a TS type identifier (e.g., `Dayjs | string`). */
        fun mappedType(klass: KClass<*>, tsIdentifier: String): Builder {
            config = config.copy(mappedTypes = config.mappedTypes + (klass.java.name to tsIdentifier)); return this
        }

        /** Accept packages for scanning (package prefixes or regex). */
        fun packageAccept(vararg patterns: String): Builder {
            config = config.copy(packageAccept = patterns.toList()); return this
        }

        /** Add to accepted packages (package prefixes or regex). */
        fun addPackageAccept(vararg patterns: String): Builder {
            config = config.copy(packageAccept = config.packageAccept + patterns.toList()); return this
        }

        /** Include specific API controllers by class (adds to packageAccept). */
        inline fun <reified T> includeApi(): Builder = includeApi(T::class)
        fun includeApi(vararg classes: KClass<*>): Builder {
            config = config.copy(packageAccept = config.packageAccept + classes.map { it.java.name })
            return this
        }

        /** Reject packages from scanning (package prefixes or regex). */
        fun packageReject(vararg patterns: String): Builder {
            config = config.copy(packageReject = patterns.toList()); return this
        }

        /** Add to rejected packages (package prefixes or regex). */
        fun addPackageReject(vararg patterns: String): Builder {
            config = config.copy(packageReject = config.packageReject + patterns.toList()); return this
        }

        /** Set optional-annotated FQCNs (treat annotated fields/params as optional). */
        fun optionalAnnotations(vararg fqns: String): Builder {
            config = config.copy(optionalAnnotations = fqns.toSet()); return this
        }

        /** Add optional-annotated FQCNs to the current set. */
        fun addOptionalAnnotations(vararg fqns: String): Builder {
            config = config.copy(optionalAnnotations = config.optionalAnnotations + fqns); return this
        }

        /** Set nullable-annotated FQCNs (treat annotated fields/params as nullable). */
        fun nullableAnnotations(vararg fqns: String): Builder {
            config = config.copy(nullableAnnotations = fqns.toSet()); return this
        }

        /** Add nullable-annotated FQCNs to the current set. */
        fun addNullableAnnotations(vararg fqns: String): Builder {
            config = config.copy(nullableAnnotations = config.nullableAnnotations + fqns); return this
        }

        /** Emit library helpers to a separate file (default name `api-lib.ts`). */
        fun emitLibAsSeparateFile(name: String = "api-lib.ts"): Builder {
            config = config.copy(emitLibFileName = name); return this
        }

        /** Group specific controllers into named API files. Key = output TS filename, value = controller FQCNs. */
        fun groupApis(grouping: Map<String, List<String>>): Builder {
            config = config.copy(groupApiFile = grouping); return this
        }

        /** Set the shared types file name (used when grouping or splitting types). */
        fun typesFileName(name: String): Builder {
            config = config.copy(typesFileName = name); return this
        }

        /** Enable emitting a separate shared types file. Optionally override the default name. */
        fun emitTypesAsSeparateFile(name: String = "api-types.ts"): Builder {
            config = config.copy(typesFileName = name); return this
        }

        /** Replace type simple names using regex→replacement mapping. */
        fun typeNameReplacements(mapping: Map<String, String>): Builder {
            config = config.copy(typeNameReplacements = mapping); return this
        }

        /** Add a single regex replacement rule for type simple names. */
        fun addTypeNameReplacement(pattern: String, replacement: String): Builder {
            config =
                config.copy(typeNameReplacements = config.typeNameReplacements + (pattern to replacement)); return this
        }

        /** Convenience overload to set external import lines. */
        fun externalImportLines(vararg pairs: Pair<String, String>): Builder = externalImportLines(pairs.toMap())

        /** Set external TS import lines: simple name → full import statement string. */
        fun externalImportLines(mapping: Map<String, String>): Builder {
            config = config.copy(externalImportLines = mapping); return this
        }

        /** Add one external TS import line. */
        fun addExternalImportLine(name: String, importLine: String): Builder {
            config = config.copy(externalImportLines = config.externalImportLines + (name to importLine)); return this
        }

        /** Set static header lines to be written at the top of every generated TS file. */
        fun headerLines(vararg lines: String): Builder {
            config = config.copy(headerLines = config.headerLines + lines); return this
        }

        fun eslintDisable(
            disableNoExplicitAny: Boolean = true,
            disableNoUnusedVars: Boolean = true,
            vararg otherRulesToDisable: String,
        ): Builder {
            val lines = config.headerLines.toMutableList()
            if (disableNoExplicitAny) lines += "/* eslint-disable @typescript-eslint/no-explicit-any */"
            if (disableNoUnusedVars) lines += "/* eslint-disable @typescript-eslint/no-unused-vars */"
            otherRulesToDisable.forEach { rule ->
                lines += "/* eslint-disable $rule */"
            }
            return headerLines(*lines.toTypedArray())
        }

        /** Whether to detect boolean isX() getters and strip "is" prefix. Default true (Jackson default). */
        fun autoDetectIsGetters(enabled: Boolean): Builder {
            config = config.copy(autoDetectIsGetters = enabled); return this
        }

        /** Whether to also strip "is" prefix from non-boolean isX() methods. Default true (Jackson 2.14+). */
        fun allowIsGettersForNonBoolean(enabled: Boolean): Builder {
            config = config.copy(allowIsGettersForNonBoolean = enabled); return this
        }

        /** Whether to use strict JavaBeans naming (getURL -> URL). Default false (Jackson default: getURL -> url). */
        fun useStdBeanNaming(enabled: Boolean): Builder {
            config = config.copy(useStdBeanNaming = enabled); return this
        }
    }

}
