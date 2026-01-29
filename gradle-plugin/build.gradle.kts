plugins {
    id("kotlin-conventions")
    id("com.gradle.plugin-publish")
}

group = rootProject.group.toString()
version = rootProject.version.toString()

dependencies {
    implementation(project(":core"))
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("reflect"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/IodeSystems/typescript-generator"
    vcsUrl = website
    plugins {
        create("typescriptGenerator") {
            id = "com.iodesystems.typescript-generator"
            implementationClass = "com.iodesystems.ts.TypeScriptGeneratorPlugin"
            displayName = "Typescript Generator Gradle Plugin"
            description = """
                Generates Typescript interfaces from JVM classes.
            """.trimIndent()
            tags.addAll(listOf("typescript", "codegen", "typescript-generator"))
        }
    }
}

tasks.publishPlugins {
    mustRunAfter(tasks.publish)
}