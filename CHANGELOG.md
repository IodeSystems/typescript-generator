# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.0.17] - Unreleased

### Added

- **Meta-annotation support for `@JsonTypeInfo`**: Custom annotations like `@JsonUnion` that alias `@JsonTypeInfo` via Spring's `@AliasFor` are now fully supported. This allows creating reusable union type annotations:
  ```kotlin
  @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
  annotation class JsonUnion(
      @get:AliasFor(annotation = JsonTypeInfo::class, attribute = "property")
      val property: String = "_type"
  )
  ```

- **Meta-annotation support for `@RequestMapping`**: Custom controller annotations like `@ApiController` that combine `@RestController` and `@RequestMapping` via `@AliasFor` now correctly resolve the base path:
  ```kotlin
  @RestController
  @RequestMapping
  annotation class ApiController(
      @get:AliasFor(annotation = RequestMapping::class, attribute = "path")
      val value: String
  )
  ```

- **Jackson `is`-prefix getter support**: Boolean properties with `is`-prefix getters (e.g., `isEnabled()`) are now properly extracted. New configuration options control naming behavior:
  - `jacksonIsPrefixGetterRemovesPrefix`: When `true` (default), `isEnabled()` → `enabled`
  - `jacksonIsPrefixGetterKeepsPrefix`: When `true`, `isEnabled()` → `isEnabled`

- **Alphabetically sorted output**: Generated TypeScript types and API classes are now sorted alphabetically for deterministic, diff-friendly output.

### Fixed

- **Generic type parameters in mapped types**: Types like `Optional<T>` now correctly reify the generic parameter (e.g., `Optional<String>` → `string | null` instead of `T | null`).

- **Direct union member references**: When a union member (e.g., `Ref.Loc`) is referenced directly instead of its parent union type (`Ref`), it now correctly includes the discriminator field.

- **Nested sealed interface hierarchies**: Classes that extend other union members (e.g., `Ref.Bu` extends `Ref.Org`) are now correctly included in the union. Classes implementing multiple `@JsonTypeInfo` sealed types are correctly assigned to only one union.

- **Enum caching**: Enums are now properly cached to avoid duplicate processing.

- **Gradle classloader isolation**: Annotation lookup now loads annotation classes from the target's classloader, properly handling Gradle plugin classloader isolation. This ensures `@AliasFor` meta-annotations work correctly when the generator runs as a Gradle plugin.

- **Annotation classes excluded from API generation**: Annotation classes that are meta-annotated with `@RestController` (like `@ApiController`) are now excluded from API generation.

### Changed

- Annotation lookup for `Class`, `Method`, and `Field` now uses Spring's `AnnotatedElementUtils.findMergedAnnotation()` internally, enabling proper `@AliasFor` resolution in all cases.

## [0.0.16] - 2025-12-15

### Fixed

- **Generic type parameters in imports**: Import statements no longer include generic type parameters (e.g., `import { Foo<T> }` → `import { Foo }`).

### Dependencies

- Bumped `org.springframework:spring-web` from 6.2.1 to 6.2.8.

## [0.0.15] - Previous Release

See git history for earlier changes.
