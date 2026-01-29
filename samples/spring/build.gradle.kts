import java.time.OffsetDateTime

repositories {
    mavenLocal()
    mavenCentral()
}

plugins {
    kotlin("jvm")
    id("idea")
    id("com.github.node-gradle.node")
    id("com.iodesystems.typescript-generator")
    id("org.springframework.boot")
}

typescriptGenerator {
    config {
        packageScan("com.iodesystems.ts.sample.api")
        emitLibAsSeparateFile("api-lib.ts")
        emitTypesAsSeparateFile("api-types.ts")
        emitReactHelpers()
        outputDirectory("src/main/ui/gen")
        externalImportLines(
            mapOf(
                "Dayjs" to "import type {Dayjs} from 'dayjs'"
            )
        )
        mapType(OffsetDateTime::class, "Dayjs")
    }
}

// When we build, we should also generateTypescript
tasks.build { dependsOn(tasks.generateTypescript) }

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
    testImplementation(kotlin("test"))
    testImplementation("com.microsoft.playwright:playwright:1.49.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
}

tasks.test {
    // Ensure Node and dependencies are available before running tests
    dependsOn("npmInstall")
    useJUnitPlatform()
}