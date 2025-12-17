repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
}

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.kotlin.plugin)
    implementation(libs.gradle.node.plugin)
    implementation(libs.gradle.spring.boot.plugin)
    implementation(libs.gradle.typescript.generator)
}
