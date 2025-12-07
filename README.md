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

## Spring Boot Support

Out of the box, the generator looks for Spring MVC/Web annotations and extracts controllers, methods, parameters, and models. Use `basePackages`, `includeApiIncludes`, and `includeApiExcludes` to control the scope.

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
@RestController
@RequestMapping
class KitchenSink {

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
    sealed interface Union {
        data object Ok : Union
        data object Uhoh : Union
    }

    interface IContainer<Q> { val item: Q }
    open class Container<T>(override val item: T) : IContainer<T>
    class Request<A, B>(item: A, val items: List<B>) : Container<A>(item)
    data class Other(val value: String)

    @PostMapping
    fun post(@RequestBody req: Map<String, Request<String, Int>>): Union = error("test")

    data object Get {
        data class Response(val items: List<String> = emptyList())
    }

    @GetMapping
    fun get(): List<Get.Response?> = error("test")

    @GetMapping("/{id}")
    fun path(@PathVariable id: Long) {}

    data class SearchQuery(
        val q: String,
        @RequestParam(required = false) val limit: Int?
    )

    @GetMapping("/search")
    fun search(@RequestParam("q") q: String, @RequestParam(required = false) limit: Int?): List<Int> = error("test")

    @PostMapping("/optional")
    fun optional(@RequestBody req: Other?) {}
}
```

Generated TypeScript (abridged to show types and API class; helper lib omitted):

```ts
export type EmitterTestKitchenSinkIContainer<Q> = {
  item: Q
}
export type EmitterTestKitchenSinkContainer<T> = EmitterTestKitchenSinkIContainer<T> & {
  item: T
}
export type EmitterTestKitchenSinkRequest<A,B> = EmitterTestKitchenSinkContainer<A> & {
  items: Array<B>
  item: A
}
export type EmitterTestKitchenSinkUnionOk = {
  "@type": "Ok"
}
export type EmitterTestKitchenSinkUnionUhoh = {
  "@type": "Uhoh"
}
export type EmitterTestKitchenSinkUnionUnion = EmitterTestKitchenSinkUnionOk | EmitterTestKitchenSinkUnionUhoh
export type EmitterTestKitchenSinkGetResponse = {
  items?: Array<string> | undefined
}
export type EmitterTestKitchenSinkSearchQuery = {
  q: string
  limit?: number | null | undefined
}
export type EmitterTestKitchenSinkOther = {
  value: string
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