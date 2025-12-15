plugins {
    id("kotlin-conventions")
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

dependencies {
    implementation(libs.logback)
    implementation(libs.classgraph)
    implementation(libs.kotlinx.metadata.jvm)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.spring.webmvc)
    implementation(libs.jackson.databind)
    testImplementation(kotlin("test"))
}

// Ensure test-project classpath is available before core tests run (for ExternalClassPathTest)
tasks.named("compileTestKotlin") {
    dependsOn(":test-project:test-core:writeClasspath")
}


