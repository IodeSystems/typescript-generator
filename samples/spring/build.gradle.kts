import java.time.OffsetDateTime

repositories {
    mavenLocal()
    mavenCentral()
}

plugins {
    kotlin("jvm")
    id("idea")
    id("com.github.node-gradle.node")
    // In your code, you should use:
    id("com.iodesystems.typescript-generator")
}

generateTypescript {
    emitLibFileName = "api-lib.ts"
    typesFileName = "api-types.ts"
    outputDirectory = "src/main/ui/gen"
    externalImportLines.putAll(
        mapOf(
            "Dayjs" to "import type {Dayjs} from 'dayjs'"
        )
    )
    mappedTypes.putAll(
        mapOf(
            OffsetDateTime::class.qualifiedName to "Dayjs",
        )
    )
}

idea {
    module {
        sourceDirs.add(file("src/main/ui"))
    }
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

node {
    download = true
    version = "24.11.1"
}

dependencies {
    implementation(libs.logback)
    implementation(libs.spring.boot.starter.web)
}