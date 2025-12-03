package com.iodesystems.ts

import com.iodesystems.ts.extractor.ApiExtractor
import com.iodesystems.ts.extractor.JvmExtractor
import com.iodesystems.ts.extractor.SpringApiExtractor
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import kotlin.reflect.KClass


data class Config(
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
    // Predicate to include or exclude discovered API controllers by their JVM FQN.
    // Default includes all APIs.
    val includeApi: (String) -> Boolean = { true },
    val customNaming: (pkg: String, cls: String) -> String = { _, cls ->
        cls
            .replace(".", "")
            .replace("$", "")
    },
    // If set via emitLibAsSeparateFile(), callers may choose to write library helpers to a separate file.
    // Default null means do not imply separate output.
    val emitLibFileName: String? = null,
    // Group API controllers into files: map of output filename -> list of controller JVM FQNs.
    // When provided, we will emit one API file per entry, a lib file (api-lib.ts), and a shared types file.
    val groupApiFile: Map<String, List<String>>? = null,
    // Types file name used when groupApiFile is set (and in future when splitting types is enabled).
    val typesFileName: String = "api-types.ts",
) {

    val jvmExtractor: com.iodesystems.ts.extractor.JvmExtractor by lazy {
        _root_ide_package_.com.iodesystems.ts.extractor.JvmExtractor(
            this
        )
    }
    val apiExtractor: com.iodesystems.ts.extractor.ApiExtractor by lazy {
        _root_ide_package_.com.iodesystems.ts.extractor.SpringApiExtractor(
            this
        )
    }

    data class Builder(
        val config: Config = Config(),
    ) {
        fun outputDirectory(dir: String) = copy(config = config.run {
            copy(outputDirectory = dir)
        })

        fun mappedType(klass: KClass<*>, tsIdentifier: String) = copy(config = config.run {
            copy(mappedTypes = mappedTypes + (klass.java.name to tsIdentifier))
        })

        fun customNaming(
            t: (
                pkg: String, cls: String
            ) -> String
        ) = copy(config = config.run {
            copy(customNaming = t)
        })

        fun basePackages(vararg pkgs: String) = copy(config = config.run {
            copy(basePackages = pkgs.toList())
        })

        fun includeApi(predicate: (String) -> Boolean) = copy(config = config.run {
            copy(includeApi = predicate)
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
    }

}
