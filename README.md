# TypeScript Generator

A Kotlin-based tool for generating TypeScript interfaces from Java/Kotlin classes, particularly useful for Spring Boot applications.

## Overview

This project provides:
- A Gradle plugin to automatically generate TypeScript types and API clients from your Java or Kotlin classes.
- A small programmatic API you can call from Kotlin/Java.

It’s designed to work well with Spring Boot REST controllers: it scans your code and dependencies, extracts API endpoints and models, and emits TypeScript types plus a tiny client helper.

## Features

- Generate TypeScript type aliases for your Java/Kotlin models
- Extract Spring REST controllers and methods into a simple TypeScript API surface
- Customizable type mappings and name replacements
- Optional/nullable handling via annotations
- Flexible output layout: single file or split into lib/api/types files and/or grouped by controller
- Gradle plugin integration and programmatic API

## Usage

### As a Gradle Plugin

Add the plugin to your `build.gradle.kts` (root or module as appropriate):

```kotlin
plugins {
    id("com.iodesystems.typescript-generator")
}
```

Configure the generator. The extension and the task are both named `generateTypescript`.
Note: properties use Gradle `Property`/`ListProperty`/`MapProperty`, so in Kotlin DSL use `.set(...)` or `.add(...)`/`.addAll(...)` as shown:

```kotlin
generateTypescript {
    // Where files are written (default: "./")
    outputDirectory.set("src/main/ui/generated")

    // Packages to scan for API/controllers and models
    basePackages.set(listOf("com.example.api"))

    // Clean the output directory before writing
    cleanOutputDir.set(true)

    // Map JVM types to TypeScript
    mappedTypes.set(mapOf(
        "java.time.OffsetDateTime" to "string",
        "java.time.LocalDate" to "string",
        "java.time.LocalTime" to "string",
    ))

    // Treat fields/params with these annotations as optional and/or nullable
    optionalAnnotations.set(setOf(
        "org.jetbrains.annotations.Nullable",
    ))
    nullableAnnotations.set(setOf(
        "org.jetbrains.annotations.Nullable",
    ))

    // Include/exclude APIs (by FQN or regex)
    includeApiIncludes.set(listOf("com.example.api..*"))
    includeApiExcludes.set(listOf("com.example.internal..*"))

    // Replace type simple names via regex → replacement
    typeNameReplacements.set(mapOf(
        "[.$]" to "",
    ))

    // Control output file layout
    // If you set a separate lib file, helpers go to that file; otherwise emitted inline in api.ts
    emitLibFileName.set("api-lib.ts")
    // Group specific controllers into separate API files (keys are filenames)
    groupApiFile.set(
        mapOf(
            "users-api.ts" to listOf("com.example.api.UserController"),
            "orders-api.ts" to listOf("com.example.api.OrderController"),
        )
    )
    // When grouping, you can also split shared types
    typesFileName.set("api-types.ts")

    // Emit custom import lines for external TS types you reference
    externalImportLines.set(
        mapOf(
            "Dayjs" to "import { Dayjs } from 'dayjs'",
        )
    )
}
```

Run the task:

```bash
./gradlew generateTypescript
```

What gets generated:
- Single-file default: `api.ts` containing types and API helpers.
- If `emitLibFileName` is set to `api-lib.ts`: helpers go to `api-lib.ts`, types remain in `api.ts` unless `typesFileName` is set.
- If `groupApiFile` is set: one file per entry (e.g., `users-api.ts`, `orders-api.ts`), a `api-lib.ts` (unless overridden), and a shared types file if `typesFileName` is set (default `api-types.ts`).

Build integration:
- The plugin wires `processResources` to depend on `generateTypescript`, and it depends on `compileJava`/`compileKotlin` tasks to ensure classpaths are available.

### Programmatic Usage

```kotlin
val generator = com.iodesystems.ts.TypeScriptGenerator.build { cfg ->
    cfg
        .outputDirectory("./generated")
        .basePackages("com.example.api")
        .includeApis("com.example.api..*")
        .emitLibAsSeparateFile() // "api-lib.ts"
        .emitTypesAsSeparateFile() // "api-types.ts"
}

// Build and write files
val output = generator.generate()
output.write()
```

## Configuration Options

- `outputDirectory` (String): Directory where generated files are written. Default: `./`
- `cleanOutputDir` (Boolean): Delete output files before writing. Default: `false`
- `basePackages` (List<String>): Packages to scan for classes/controllers. Default: empty (scan everything on classpath)
- `mappedTypes` (Map<String,String>): Map JVM FQCN to TypeScript identifier.
- `optionalAnnotations` (Set<String>): FQCNs that mark a field/param/getter as optional.
- `nullableAnnotations` (Set<String>): FQCNs that mark a field/param/getter as nullable.
- `includeApiIncludes` / `includeApiExcludes` (List<String>): FQCN or regex patterns to include/exclude APIs.
- `typeNameReplacements` (Map<Regex,String>): Regex replacement rules for simple type names.
- `emitLibFileName` (String?): If set, emit API helpers to this file (commonly `api-lib.ts`).
- `groupApiFile` (Map<String, List<String>>?): Group controllers (by FQCN or regex) into specific output files.
- `typesFileName` (String?): When grouping or splitting types, the output types file name (e.g., `api-types.ts`).
- `externalImportLines` (Map<String,String>): Map of simple type name to import line to emit at top of files.

### Advanced

- `classPathUrls` (Set<String>, Gradle: `SetProperty<String>`): Custom classpath entries the scanner should use. The Gradle plugin auto-populates this from your `main` source set outputs and compile classpath; you typically do not need to set this manually unless you are invoking the generator programmatically or have a non-standard build layout.

## Spring Boot Support

Out of the box, the generator looks for Spring MVC/Web annotations and extracts controllers, methods, parameters, and models. Use `basePackages`, `includeApiIncludes`, and `includeApiExcludes` to control the scope.

## Building

```bash
./gradlew build
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Run tests with `./gradlew test`
5. Submit a pull request

## License

MIT License