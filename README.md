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
    id("com.iodesystems.typescript-generator") version "0.0.19-SNAPSHOT"
}
```

Configure the generator using the `typescriptGenerator` extension (a legacy `generateTypescript` alias also exists).
Use the internal mutable `Config` builder via `config { ... }`:

```kotlin
typescriptGenerator {
    config {
        // Where files are written (default: "./")
        outputDirectory("src/main/ui/generated")

        // Package patterns to scan (package prefixes or regex)
        // This controls both ClassGraph scanning scope and API filtering
        packageScan("com.example.api")

        // Package patterns to ignore (package prefixes or regex)
        packageIgnore("com.example.internal")

        // Or include specific API controllers by class
        includeApi<UserController>()
        includeApi(OrderController::class, ProductController::class)

        // Clean the output directory before writing
        cleanOutputDir()

        // Map JVM types to TypeScript
        mapType(
            mapOf(
                "java.time.OffsetDateTime" to "string",
                "java.time.LocalDate" to "string",
                "java.time.LocalTime" to "string",
            )
        )
        // Or map individual types
        mapType(OffsetDateTime::class, "Dayjs")

        // Explicitly include types by FQCN (even if not referenced by API)
        include("com.example.MyType")
        include<MyOtherType>()

        // Set type name aliases (FQCN → TypeScript name, bypasses typeNameReplacements)
        alias(
            mapOf(
                "com.example.ByteArray" to "Bytes"
            )
        )
        alias(ByteArray::class, "Bytes")

        // Treat fields/params with these annotations as optional and/or nullable
        optionalAnnotations("org.jetbrains.annotations.Nullable")
        nullableAnnotations("org.jetbrains.annotations.Nullable")

        // Replace type simple names via regex → replacement
        typeNameReplacements(mapOf("[.$]" to ""))
        addTypeNameReplacement("[.$]", "")

        // Control output file layout
        // If you set a separate lib file, helpers go to that file; otherwise emitted inline in api.ts
        emitLibAsSeparateFile("api-lib.ts")
        // Group specific controllers into separate API files (keys are filenames)
        groupApis(
            mapOf(
                "users-api.ts" to listOf("com.example.api.UserController"),
                "orders-api.ts" to listOf("com.example.api.OrderController"),
            )
        )
        // When grouping, you can also split shared types
        emitTypesAsSeparateFile("api-types.ts")
        // Or set a custom types file name
        typesFileName("my-types.ts")

        // Emit custom import lines for external TS types you reference
        externalImportLines(
            mapOf(
                "Dayjs" to "import type { Dayjs } from 'dayjs'",
            )
        )
        addExternalImportLine("Dayjs", "import type { Dayjs } from 'dayjs'")

        // Add header lines to all generated files (e.g., lint directives)
        headerLines("/* eslint-disable */", "/* prettier-ignore */")
        // Or use the convenience method for ESLint
        eslintDisable()

        // Jackson naming options (advanced)
        autoDetectIsGetters(true)  // Strip "is" prefix from boolean isX() getters
        allowIsGettersForNonBoolean(true)  // Also strip "is" from non-boolean isX()
        useStdBeanNaming(false)  // Use strict JavaBeans naming (getURL -> URL vs url)

        // Other options
        setsAsArrays(true)  // Treat Java/Kotlin Set as TypeScript array
        includeRefComments(true)  // Include JVM class name comments in output
    }
}
```

Run the task:

```bash
./gradlew generateTypescript
```

What gets generated:
- Single-file default: `api.ts` containing types and API helpers.
- If you call `emitLibAsSeparateFile("api-lib.ts")`: helpers go to `api-lib.ts`, types remain in `api.ts` unless you also split types.
- If you call `groupApis(...)`: one file per entry (e.g., `users-api.ts`, `orders-api.ts`), a `api-lib.ts` (unless overridden), and a shared types file if `emitTypesAsSeparateFile(...)`/`typesFileName(...)` is set (default `api-types.ts`).

Build integration:
- The plugin wires `processResources` to depend on `generateTypescript`, and it depends on `compileJava`/`compileKotlin` tasks to ensure classpaths are available.

### Programmatic Usage

```kotlin
val generator = com.iodesystems.ts.TypeScriptGenerator.build {
    outputDirectory("./generated")
    packageScan("com.example.api")
    packageIgnore("com.example.internal")
    emitLibAsSeparateFile() // "api-lib.ts"
    emitTypesAsSeparateFile() // "api-types.ts"
    mapType(java.time.OffsetDateTime::class, "Dayjs")
    externalImportLines(mapOf("Dayjs" to "import type { Dayjs } from 'dayjs'"))
}

// Build and write files
val output = generator.generate()
output.write()
```

## Configuration Options

All configuration is done through the `Config.Builder` class. Here are the available options:

### Basic Options

- `outputDirectory(dir: String)` — Directory where generated files are written. Default: `./`
- `cleanOutputDir(set: Boolean = true)` — Delete output files before writing. Default: `false`
- `includeRefComments(set: Boolean = true)` — Emit JVM class name comments in output. Default: `true`
- `setsAsArrays(set: Boolean = true)` — Treat Kotlin/Java `Set` like TypeScript arrays. Default: `true`

### Scanning & Filtering

- `packageScan(vararg patterns: String)` — Package prefixes or regex patterns for ClassGraph scanning and API filtering
- `packageIgnore(vararg patterns: String)` — Package prefixes or regex patterns to reject from scanning
- `includeApi<T>()` / `includeApi(vararg classes: KClass<*>)` — Include specific API controllers by class
- `include(vararg fqns: String)` / `include<T>()` / `include(vararg classes: KClass<*>)` — Explicitly include types by FQCN (even if not referenced by API methods)
- `exclude(vararg fqns: String)` / `exclude(vararg classes: KClass<*>)` — Replace the list of FQCNs/classes to exclude from type emission

### Type Mapping & Naming

- `mapType(map: Map<String, String>)` / `mapType(klass: KClass<*>, tsIdentifier: String)` — Map JVM types to TypeScript identifiers (e.g., `OffsetDateTime` → `"Dayjs"`)
- `alias(map: Map<String, String>)` / `alias(klass: KClass<*>, tsName: String)` — Set explicit TypeScript type names (FQCN → name, bypasses typeNameReplacements)
- `typeNameReplacements(mapping: Map<String, String>)` / `addTypeNameReplacement(pattern: String, replacement: String)` — Regex replacement rules for simple type names (e.g., `"[.$]"` → `""` to strip $ from nested class names)

### Annotations

- `optionalAnnotations(vararg fqns: String)` — FQCNs of annotations that mark fields/params as optional
- `nullableAnnotations(vararg fqns: String)` — FQCNs of annotations that mark fields/params as nullable

### Output Layout

- `emitLibAsSeparateFile(name: String = "api-lib.ts")` — Emit API helpers to a separate file
- `emitTypesAsSeparateFile(name: String = "api-types.ts")` — Emit types to a separate file
- `typesFileName(name: String)` — Set the shared types file name (used when grouping or splitting types)
- `groupApis(grouping: Map<String, List<String>>)` — Group specific controllers into named API files. Key = output TS filename, value = controller FQCNs

### Imports & Headers

- `externalImportLines(mapping: Map<String, String>)` / `externalImportLines(vararg pairs: Pair<String, String>)` — Map TypeScript type names to import statements
- `addExternalImportLine(name: String, importLine: String)` — Add a single external import line
- `headerLines(vararg lines: String)` — Lines to write at the top of every generated TS file
- `eslintDisable(disableNoExplicitAny: Boolean = true, disableNoUnusedVars: Boolean = true, vararg otherRulesToDisable: String)` — Convenience method to add ESLint disable directives

### Jackson Naming Options (Advanced)

- `autoDetectIsGetters(enabled: Boolean)` — Detect boolean `isX()` getters and strip "is" prefix. Default: `true` (Jackson default)
- `allowIsGettersForNonBoolean(enabled: Boolean)` — Also strip "is" prefix from non-boolean `isX()` methods. Default: `true` (Jackson 2.14+)
- `useStdBeanNaming(enabled: Boolean)` — Use strict JavaBeans naming (e.g., `getURL()` → `"URL"` instead of `"url"`). Default: `false` (Jackson default)

## Spring Boot Support

Out of the box, the generator looks for Spring MVC/Web annotations and extracts controllers, methods, parameters, and models. Use `packageScan` and `packageIgnore` to control the scope, or `includeApi` to target specific controller classes.

## Samples

- Spring Boot sample: `samples/spring`
  - Demonstrates applying the Gradle plugin in a simple Spring HTTP API and generating TypeScript outputs.
  - Generate the sample’s TypeScript files from the repo root:
    ```bash
    cd samples/spring
    ./gradlew :generateTypescript
    ```
  - Outputs are written to `samples/spring/src/main/ui/gen/` (e.g., `api.ts`, `api-lib.ts`, `api-types.ts` depending on configuration).
  - You can explore the sample’s `build.gradle.kts` for a concrete plugin configuration.

## Kitchen Sink Example

Below is a compact Spring controller showcasing many supported features (generics, nested types, unions, path/query params, optional/nullable, lists, maps). This exact class is used in the test suite: [EmitterTest.kt](/core/src/test/kotlin/com/iodesystems/ts/emitter/EmitterTest.kt)

```kotlin
@RestController
@RequestMapping("herp/derp")
class KitchenSink {

  @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
  sealed interface Union {
    data object Ok : Union
    data object Uhoh : Union
  }

  interface IContainer<Q> {
    val item: Q
  }

  open class Container<T>(
    override val item: T
  ) : IContainer<T>

  class Request<A, B>(
    item: A,
    val items: List<B>
  ) : Container<A>(item)

  data class Other(val value: String)

  @PostMapping
  fun post(
    @RequestBody
    req: Map<String, Request<String, Int>>
  ): Union = error("test")

  data object Get {
    data class Response(
      val items: List<String> = emptyList(),
    )
  }

  @GetMapping
  fun get(): List<Get.Response?> = error("test")

  @GetMapping("/{id}")
  fun path(
    @PathVariable id: Long
  ) {
  }

  data class SearchQuery(
    val q: String,
    @RequestParam(required = false)
    val limit: Int?
  )

  @GetMapping("/search")
  fun search(
    @RequestParam("q") q: String,
    @RequestParam(required = false) limit: Int?
  ): List<Int> = error("test")

  @PostMapping("/optional")
  fun optional(
    @RequestBody req: Other?
  ) {
  }
}
```

Generated TypeScript (abridged to show types and API class; helper lib omitted):

```ts
//<api.ts>

/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Union}
 * TYPE ref:
 * - {@link EmitterTestKitchenSinkUnionOk}
 * - {@link EmitterTestKitchenSinkUnionUhoh}
 */
export type EmitterTestKitchenSinkUnion = {
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Union$Ok}
 * TYPE ref:
 * - {@link EmitterTestKitchenSinkUnionUnion}
 */
export type EmitterTestKitchenSinkUnionOk = EmitterTestKitchenSinkUnion & {
  "@type": "Ok"
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Union$Uhoh}
 * TYPE ref:
 * - {@link EmitterTestKitchenSinkUnionUnion}
 */
export type EmitterTestKitchenSinkUnionUhoh = EmitterTestKitchenSinkUnion & {
  "@type": "Uhoh"
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Union#Union}
 * METHOD ref:
 * - {@link EmitterTestKitchenSink#post}
 */
export type EmitterTestKitchenSinkUnionUnion = EmitterTestKitchenSinkUnion & (EmitterTestKitchenSinkUnionOk | EmitterTestKitchenSinkUnionUhoh)
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Request}
 * METHOD ref:
 * - {@link EmitterTestKitchenSink#post}
 */
export type EmitterTestKitchenSinkRequest<A,B> = EmitterTestKitchenSinkContainer<A> & {
  items: Array<B>
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Container}
 */
export type EmitterTestKitchenSinkContainer<T> = EmitterTestKitchenSinkIContainer<T>
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$IContainer}
 */
export type EmitterTestKitchenSinkIContainer<Q> = {
  item: Q
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink#searchQuery}
 */
export type EmitterTestKitchenSinkSearchQuery = {
  q: string
  limit?: number | null | undefined
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Other}
 * METHOD ref:
 * - {@link EmitterTestKitchenSink#optional}
 */
export type EmitterTestKitchenSinkOther = {
  value: string
}
/**
 * Jvm {@link com.iodesystems.ts.emitter.EmitterTest$KitchenSink$Get$Response}
 * METHOD ref:
 * - {@link EmitterTestKitchenSink#get}
 */
export type EmitterTestKitchenSinkGetResponse = {
  items?: Array<string> | undefined
}
export class EmitterTestKitchenSink {
  constructor(private opts: ApiOptions = {}) {}
  post(req: Record<string,EmitterTestKitchenSinkRequest<string,number>>): AbortablePromise<EmitterTestKitchenSinkUnionUnion> {
    return fetchInternal(this.opts, "herp/derp", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  get(): AbortablePromise<Array<EmitterTestKitchenSinkGetResponse | null>> {
    return fetchInternal(this.opts, "herp/derp", {
      method: "GET"
    }).then(r=>r.json())
  }
  path(path: { id: string | number }): AbortablePromise<void> {
    return fetchInternal(this.opts, "herp/derp/{id}".replace("{id}", String(path.id)), {
      method: "GET"
    }).then(r=>{})
  }
  search(query: EmitterTestKitchenSinkSearchQuery): AbortablePromise<Array<number>> {
    return fetchInternal(this.opts, flattenQueryParams("herp/derp/search", query, null), {
      method: "GET"
    }).then(r=>r.json())
  }
  optional(req: EmitterTestKitchenSinkOther | null): AbortablePromise<void> {
    return fetchInternal(this.opts, "herp/derp/optional", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>{})
  }
}

//</api.ts>

```

Note: In single-file mode, a small helper library is emitted at the top of `api.ts`. This includes:
- `ApiOptions`: Configuration object for the API client (base URL, headers, etc.)
- `AbortablePromise<T>`: A Promise with an `abort()` method for canceling requests
- `fetchInternal()`: Internal fetch wrapper that handles the request lifecycle
- `flattenQueryParams()`: Helper to convert query parameter objects to URL query strings

For brevity, the helper library is omitted from this example.

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