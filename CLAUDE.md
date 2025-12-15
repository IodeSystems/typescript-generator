# TypeScript Generator

A Kotlin library that generates TypeScript API clients from Spring REST controllers.

## Project Structure

```
typescript-generator2/
├── core/                    # Main library
│   └── src/main/kotlin/com/iodesystems/ts/
│       ├── TypeScriptGenerator.kt  # Entry point
│       ├── Config.kt               # Configuration DSL
│       ├── Emitter.kt              # TypeScript code generation
│       ├── Scanner.kt              # ClassGraph scanning
│       ├── adapter/                # JSON/API framework adapters
│       │   ├── JsonAdapter.kt      # Interface for JSON libs (Jackson)
│       │   ├── JacksonJsonAdapter.kt
│       │   ├── ApiAdapter.kt       # Interface for API frameworks
│       │   └── SpringApiAdapter.kt
│       ├── extractor/              # Type extraction
│       │   ├── JvmExtractor.kt     # Orchestrates extraction
│       │   ├── RegistrationContext.kt  # Type registration & collision detection
│       │   ├── SpringApiExtractor.kt   # Extracts Spring @RestController methods
│       │   └── extractors/
│       │       ├── ClassReference.kt   # JVM Class -> TsType conversion
│       │       ├── JvmMethod.kt        # Method parameter extraction
│       │       └── ...
│       ├── model/                  # Data models
│       │   ├── TsType.kt           # TypeScript type hierarchy
│       │   └── ApiModel.kt         # API method model
│       └── lib/                    # Utilities
├── test-project/            # Test subproject for external classpath testing
│   ├── test-core/           # Contains API controllers using test-dep types
│   └── test-dep/            # External dependency types
└── buildSrc/                # Gradle build logic
```

## Architecture

### Type System (TsType)
- `TsType.Inline` - Inline type references (primitives, generics)
- `TsType.Object` - Object types with fields
- `TsType.Union` - Discriminated unions (sealed interfaces with @JsonTypeInfo)
- `TsType.Enum` - Enum types
- `TsType.Alias` - Type aliases for mapped types (provides traceability)

### Extraction Flow
1. `Scanner` uses ClassGraph to find Spring controllers
2. `SpringApiExtractor` extracts API methods from controllers
3. `JvmExtractor` orchestrates type extraction using `ClassReference`
4. `ClassReference` converts JVM types to TsType using:
   - `Class.forName()` / `scan.loadClass()` for reflection
   - Kotlin metadata for nullability (`kotlinx-metadata-jvm`)
5. `RegistrationContext` registers types and detects collisions
6. `Emitter` generates TypeScript code

### Key Features
- **Mapped Types**: User-defined JVM->TS mappings (e.g., `OffsetDateTime -> "Dayjs | string"`)
- **Type Aliases**: Mapped types emit as aliases for traceability
- **Jackson Support**: @JsonProperty, @JsonValue, @JsonTypeInfo, @JsonCreator
- **Spring Support**: @PathVariable, @RequestParam, @RequestBody
- **Collision Detection**: Throws error if custom naming causes duplicate type names
- **External Classpath**: Can extract types from jars not on compile classpath

## Running Tests

```bash
./gradlew :core:test                    # All tests
./gradlew :core:test --tests "*.SomeTest"  # Specific test
```

## Common Patterns

### Adding a Test
```kotlin
@RestController
@RequestMapping("/api")
class MyController {
    @PostMapping
    fun post(@RequestBody req: MyRequest): MyResponse = error("stub")
}

class MyTest {
    @Test
    fun testSomething() {
        val em = emitter(MyController::class)
        val content = em.ts().content()
        content.assertContains("expected output", "description")
    }
}
```

### Configuration DSL
```kotlin
TypeScriptGenerator.build {
    includeApi(MyController::class)
    mappedType(OffsetDateTime::class, "Dayjs | string")
    addTypeNameReplacement("\\$", "")  // Remove $ from nested class names
    outputDirectory("./generated")
}.generate().write()
```

## Key Files to Know

- `ClassReference.kt` - Core type conversion logic
- `Emitter.kt` - TypeScript output generation
- `RegistrationContext.kt` - Type registration with collision detection
- `TsType.kt` - Type model definitions
- `Config.kt` - All configuration options
