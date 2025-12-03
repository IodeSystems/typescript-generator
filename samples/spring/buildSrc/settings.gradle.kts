dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            val path = files("../gradle/libs.versions.toml")
            from(path)
        }
    }
}
