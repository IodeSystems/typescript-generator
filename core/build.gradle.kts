repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven {
        setUrl("https://s01.oss.sonatype.org/content/repositories/releases")
    }
}

plugins {
    kotlin("jvm")
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