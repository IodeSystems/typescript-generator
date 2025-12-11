group = "com.iodesystems.typescript-generator"
version = "0.0.11-SNAPSHOT"
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

tasks.publishToMavenLocal {
    dependsOn(
        ":core:publishToMavenLocal",
        ":gradle-plugin:publishToMavenLocal",
    )
}

val requireSign = !rootProject.hasProperty("skipSigning")

subprojects {
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
        isRequired = requireSign
        useGpgCmd()
        sign(publishing.publications)
    }

    publishing {
        if (project.name == "core") {
            publications {
                create<MavenPublication>("java") {
                    from(components["java"])
                }
            }
        }
        afterEvaluate {
            publications {
                withType<MavenPublication> {
                    artifactId = joinName(project)
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
    val signingTasks = tasks.withType<Sign>()
    signingTasks.configureEach {
        onlyIf { requireSign }
    }
    tasks.withType<AbstractPublishToMaven>().configureEach {
        dependsOn(signingTasks)
    }
}