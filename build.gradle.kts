group = "com.iodesystems.typescript-generator"
version = "0.0.6-SNAPSHOT"
description = "Typescript Client Generator"

repositories {
    mavenCentral()
    gradlePluginPortal()
    mavenLocal()
}

plugins {
    id("release-conventions")
}

private fun joinName(p: Project): String {
    var name = p.name
    val pp = p.parent
    if (pp != null && pp != rootProject) {
        name = name + "-" + joinName(pp)
    }
    return name
}
subprojects {
    val artifactName = joinName(project)

    if (listOf(
            "core",
//            "gradle-plugin"
        ).contains(artifactName)
    ) {
        apply(plugin = "java")
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        group = rootProject.group.toString()
        version = rootProject.version.toString()

        java {
            withSourcesJar()
            withJavadocJar()
        }
        signing {
            useGpgCmd()
            sign(publishing.publications)
        }
        publishing {
            publications {
                create<MavenPublication>("mavenJava") {
                    artifactId = artifactName
                    from(components["java"])
                    pom {
                        name.set(project.name)
                        description.set(project.description.let {
                            if (it.isNullOrBlank()) rootProject.description
                            else it
                        })
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
}