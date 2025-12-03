plugins {
    id("kotlin-conventions")
    id("com.gradle.plugin-publish")
    `maven-publish`
    signing
}


group = rootProject.group.toString()
version = rootProject.version.toString()

dependencies {
    implementation(project(":core"))
}

java {
    withSourcesJar()
    withJavadocJar()
}
signing {
    useGpgCmd()
    sign(publishing.publications)
}

gradlePlugin {
    website = "https://github.com/IodeSystems/typescript-generator"
    vcsUrl = website
    plugins {
        create("typescriptGeneratorPlugin") {
            id = "com.iodesystems.typescript-generator"
            implementationClass = "com.iodesystems.ts.TypeScriptGeneratorPlugin"
            description = project.description
            tags.addAll(listOf("typescript", "codegen", "typescript-generator"))
        }
    }
}