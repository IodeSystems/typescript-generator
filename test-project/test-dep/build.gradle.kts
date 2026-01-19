plugins {
    kotlin("jvm")
}

dependencies {
    // Minimal dependencies for model classes
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.20")

    // Spring for REST annotations (needed for @ApiController meta-annotation)
    implementation("org.springframework:spring-web:7.0.3")
}
