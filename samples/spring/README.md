### Spring sample — TypeScript generation

This project demonstrates generating a small TypeScript client and types from Kotlin/Spring sources using the `com.iodesystems.typescript-generator` Gradle plugin.

#### How to generate

Run the Gradle task from the project root:

```
./gradlew generateTypescript
```

Notes:
- Gradle will download Node for you (see the `node { download = true }` block in `build.gradle.kts`). No system Node install is required.
- You can safely re‑run the task; it will overwrite the generated files.

#### Where the files are generated

Configured output directory (see `build.gradle.kts` → `generateTypescript`):

- `src/main/ui/gen`

Generated files you should see after running the task:

- `src/main/ui/gen/api.ts` — typed API client for calling your Spring endpoints
- `src/main/ui/gen/api-types.ts` — shared TypeScript type declarations
- `src/main/ui/gen/api-lib.ts` — small runtime helpers used by the client

File names are configured in `build.gradle.kts`:

```
generateTypescript {
    config {
        emitLibAsSeparateFile("api-lib.ts")
        emitTypesAsSeparateFile("api-types.ts")
        outputDirectory("src/main/ui/gen")
        // ...additional mappings via builder methods
    }
}
```

#### What sources are used

The generator inspects your Kotlin sources under `src/main/kotlin` and derives the HTTP surface and DTO shapes from Spring Web annotations and data classes. In this sample, the main API lives at:

- `src/main/kotlin/com/iodesystems/ts/sample/api/SampleApi.kt`

Changes you make to controllers or DTOs will be reflected the next time you run `./gradlew generateTypescript`.

#### Typical workflow

1. Edit Kotlin controllers/DTOs under `src/main/kotlin/...`
2. Generate TS client and types: `./gradlew generateTypescript`
3. Import the generated files from `src/main/ui/gen` in your UI code

Optional tips:
- To clean generated output manually, delete the `src/main/ui/gen` directory, then re‑run the task.
- You can customize type mappings (e.g., map `java.time` types to `Dayjs`) via `mappedTypes` and add import lines via `externalImportLines` in `build.gradle.kts`.
