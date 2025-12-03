rootProject.name = "typescript-generator"

include(
    ":core",
    ":gradle-plugin",
)

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}