import nl.littlerobots.vcu.plugin.resolver.ModuleVersionCandidate

group = "com.iodesystems.ts"
version = "0.0.3-SNAPSHOT"
description =
    "Typescript Client Generator"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven {
        setUrl("https://s01.oss.sonatype.org/content/repositories/releases")
    }
    mavenLocal()
}

plugins {
    kotlin("jvm")
    id("release-conventions")
    id("nl.littlerobots.version-catalog-update") version "1.0.1"
}

versionCatalogUpdate {
    sortByKey = true
    versionSelector(object : nl.littlerobots.vcu.plugin.resolver.ModuleVersionSelector {
        override fun select(candidate: ModuleVersionCandidate): Boolean {
            val version = candidate.candidate.version
            val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }

            val regex = "^[0-9,.v-]+(-r)?$".toRegex()
            val isStable = stableKeyword || regex.matches(version)
            return isStable
        }
    })
}
