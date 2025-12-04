# TypeScript Generator

A Kotlin-based tool for generating TypeScript interfaces from Java/Kotlin classes, particularly useful for Spring Boot applications.

## Overview

This project provides a Gradle plugin that can automatically generate TypeScript interfaces from your Java or Kotlin classes. It's especially designed to work with Spring Boot applications, extracting API endpoints and generating corresponding TypeScript types.

## Features

- Generate TypeScript interfaces from Java/Kotlin classes
- Support for Spring Boot REST controllers
- Customizable type mappings
- Flexible configuration options
- Gradle plugin integration

## Usage

### As a Gradle Plugin

Add the plugin to your `build.gradle.kts`:

```kotlin
plugins {
    id("com.iodesystems.typescript-generator")
}
```

Configure the generator in your build script:

```kotlin
generateTypescript {
    outputDirectory = "src/main/ui/generated"
    basePackages = listOf("com.example.api")
}
```

### Programmatic Usage

```kotlin
val generator = TypeScriptGenerator.build { 
    it.outputDirectory("./generated") 
        .basePackages("com.example.api")
}
val output = generator.generate()
output.write()
```

## Configuration Options

- `outputDirectory`: Directory where generated files will be written
- `basePackages`: Packages to scan for classes
- `mappedTypes`: Custom type mappings from Java/Kotlin to TypeScript
- `optionalAnnotations`: Annotations that mark fields as optional
- `nullableAnnotations`: Annotations that mark fields as nullable
- `includeApiIncludes/Excludes`: Regex patterns to include/exclude APIs

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