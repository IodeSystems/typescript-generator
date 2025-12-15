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
    id("com.iodesystems.typescript-generator") version "0.0.17-SNAPSHOT"
}
```

Configure the generator. The extension and the task are both named `generateTypescript`.
Use the internal mutable `Config` builder via `config { ... }`:

```kotlin
generateTypescript {
    config {
        // Where files are written (default: "./")
        outputDirectory("src/main/ui/generated")

        // Package patterns to accept for scanning (package prefixes or regex)
        // This controls both ClassGraph scanning scope and API filtering
        packageAccept("com.example.api")

        // Package patterns to reject (package prefixes or regex)
        packageReject("com.example.internal")

        // Or include specific API controllers by class
        includeApi<UserController>()
        includeApi(OrderController::class, ProductController::class)

        // Clean the output directory before writing
        cleanOutputDir()

        // Map JVM types to TypeScript
        mappedTypes(
            mapOf(
                "java.time.OffsetDateTime" to "string",
                "java.time.LocalDate" to "string",
                "java.time.LocalTime" to "string",
            )
        )

        // Treat fields/params with these annotations as optional and/or nullable
        optionalAnnotations("org.jetbrains.annotations.Nullable")
        nullableAnnotations("org.jetbrains.annotations.Nullable")

        // Replace type simple names via regex → replacement
        typeNameReplacements(mapOf("[.$]" to ""))

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

        // Emit custom import lines for external TS types you reference
        externalImportLines(
            mapOf(
                "Dayjs" to "import { Dayjs } from 'dayjs'",
            )
        )
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
        .packageAccept("com.example.api")
        .packageReject("com.example.internal")
        .emitLibAsSeparateFile() // "api-lib.ts"
        .emitTypesAsSeparateFile() // "api-types.ts"
}

// Build and write files
val output = generator.generate()
output.write()
```

## Configuration Options

- `outputDirectory` (String) — builder: `outputDirectory(dir)`. Directory where generated files are written. Default: `./`
- `cleanOutputDir` (Boolean) — builder: `cleanOutputDir()`. Delete output files before writing. Default: `false`
- `packageAccept` (List<String>) — builder: `packageAccept(vararg)` / `addPackageAccept(vararg)` / `includeApi<T>()` / `includeApi(vararg KClass)`. Package prefixes or regex patterns to accept for ClassGraph scanning and API filtering. When using `includeApi`, the class names are added here and the package is extracted for scanning.
- `packageReject` (List<String>) — builder: `packageReject(vararg)` / `addPackageReject(vararg)`. Package prefixes or regex patterns to reject from scanning and API filtering.
- `mappedTypes` (Map<String,String>) — builder: `mappedTypes(map)` / `mappedType(KClass, String)`. Map JVM FQCN to TypeScript identifier.
- `optionalAnnotations` (Set<String>) — builder: `optionalAnnotations(vararg)` / `addOptionalAnnotations(vararg)`. FQCNs that mark a field/param/getter as optional.
- `nullableAnnotations` (Set<String>) — builder: `nullableAnnotations(vararg)` / `addNullableAnnotations(vararg)`. FQCNs that mark a field/param/getter as nullable.
- `typeNameReplacements` (Map<Regex,String>) — builder: `typeNameReplacements(map)` / `addTypeNameReplacement(pattern, replacement)`. Regex replacement rules for simple type names.
- `emitLibFileName` (String?) — builder: `emitLibAsSeparateFile(name)`. If set, emit API helpers to this file (commonly `api-lib.ts`).
- `groupApiFile` (Map<String, List<String>>?) — builder: `groupApis(map)`. Group controllers (by FQCN) into specific output files.
- `typesFileName` (String?) — builder: `typesFileName(name)` / `emitTypesAsSeparateFile(name)`. When grouping or splitting types, the output types file name (e.g., `api-types.ts`).
- `externalImportLines` (Map<String,String>) — builder: `externalImportLines(map)` / `addExternalImportLine(name, line)`. Map of simple type name to import line to emit at top of files.

## Spring Boot Support

Out of the box, the generator looks for Spring MVC/Web annotations and extracts controllers, methods, parameters, and models. Use `packageAccept` and `packageReject` to control the scope, or `includeApi` to target specific controller classes.

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

Below is a compact Spring controller showcasing many supported features (generics, nested types, unions, path/query params, optional/nullable, lists, maps). This exact class is used by the tests (`EmitterTest#kitchenSink`).

```kotlin
// In an object: com.iodesystems.ts.emitter.EmitterTest
@RestController
@RequestMapping
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
  post(req: Record<string,EmitterTestKitchenSinkRequest<string,number>>): Promise<EmitterTestKitchenSinkUnionUnion> {
    return fetchInternal(this.opts, "/", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>r.json())
  }
  get(): Promise<Array<EmitterTestKitchenSinkGetResponse | null>> {
    return fetchInternal(this.opts, "/", {
      method: "GET"
    }).then(r=>r.json())
  }
  path(path: { id: string | number }): Promise<void> {
    return fetchInternal(this.opts, "/{id}".replace("{id}", String(path.id)), {
      method: "GET"
    }).then(r=>{})
  }
  search(query: EmitterTestKitchenSinkSearchQuery): Promise<Array<number>> {
    return fetchInternal(this.opts, flattenQueryParams("/search", query, null), {
      method: "GET"
    }).then(r=>r.json())
  }
  optional(req: EmitterTestKitchenSinkOther | null): Promise<void> {
    return fetchInternal(this.opts, "/optional", {
      method: "POST",
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(req)
    }).then(r=>{})
  }
}

//</api.ts>

```

Note: In single-file mode, a small helper library (`ApiOptions`, `fetchInternal`, `flattenQueryParams`) is emitted at the top of `api.ts`. For brevity it’s omitted here.

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