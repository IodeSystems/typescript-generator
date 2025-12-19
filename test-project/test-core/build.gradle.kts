plugins {
    kotlin("jvm")
}

dependencies {
    // Project dependency - types from different package
    implementation(project(":test-project:test-dep"))

    // Spring for REST annotations
    implementation("org.springframework:spring-web:7.0.2")

    // Jackson for JSON handling
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
}

// Output the runtime classpath for ExternalClassPathTest in core module
tasks.register("writeClasspath") {
    dependsOn("compileKotlin", ":test-project:test-dep:compileKotlin")
    val outputFile = layout.buildDirectory.file("classpath.txt")
    outputs.file(outputFile)
    doLast {
        val cp = configurations.runtimeClasspath.get().files.map { it.absolutePath } +
            listOf(
                layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath,
                project(":test-project:test-dep").layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath
            )
        outputFile.get().asFile.writeText(cp.joinToString("\n"))
    }
}
