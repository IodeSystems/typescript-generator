import nl.littlerobots.vcu.plugin.resolver.ModuleVersionCandidate

group = "com.iodesystems.ts"
version = "0.0.3-SNAPSHOT"
description = "Typescript Client Generator"

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

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")

    group = rootProject.group.toString()
    version = rootProject.version.toString()

    java {
        withSourcesJar()
        withJavadocJar()
    }
    publishing {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(rootProject.description)
                    url.set("https://iodesystems.github.io/typescript-generator/")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://www.opensource.org/licenses/mit-license.php")
                        }
                    }
                    developers {
                        developer {
                            organization.set("iodesystems")
                            organizationUrl.set("https://iodesystems.com")
                            id.set("nthalk")
                            name.set("Carl Taylor")
                            email.set("carl@etaylor.me")
                            roles.add("owner")
                            roles.add("developer")
                            timezone.set("-8")
                        }
                    }
                    scm {
                        connection.set("scm:git:git@github.com:IodeSystems/typescript-generator.git")
                        developerConnection.set("scm:git:git@github.com:IodeSystems/typescript-generator.git")
                        url.set("https://iodesystems.github.io/typescript-generator/")
                        tag.set("${rootProject.version}")
                    }
                }
            }
        }
    }
}