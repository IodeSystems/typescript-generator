rootProject.name = "typescript-generator"

include(
    ":core",
    ":gradle-plugin",
    ":test-project:test-dep",
    ":test-project:test-core",
)

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}