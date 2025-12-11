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
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/IodeSystems/typescript-generator"
    vcsUrl = website
    description = project.description
    plugins {
        create("typescriptGenerator") {
            id = "com.iodesystems.typescript-generator"
            implementationClass = "com.iodesystems.ts.TypeScriptGeneratorPlugin"
            tags.addAll(listOf("typescript", "codegen", "typescript-generator"))
            displayName = "Typescript Generator Gradle Plugin"
        }
    }
}