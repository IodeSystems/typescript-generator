import org.gradle.kotlin.dsl.kotlin
import java.time.Duration

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka-javadoc")
    id("io.github.gradle-nexus.publish-plugin")
}

val javadocJar: TaskProvider<Jar> by tasks.registering(Jar::class) {
    dependsOn(tasks.dokkaGeneratePublicationJavadoc)
    archiveClassifier.set("javadoc")
    from(tasks.dokkaGeneratePublicationJavadoc.get().outputDirectory)
}
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifact(javadocJar)
            artifact(tasks.kotlinSourcesJar) {
                classifier = "sources"
            }
            pom {
                name.set(project.name)
                description.set(project.description)
                url.set("https://iodesystems.github.io/typescript-generator/")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://www.opensource.org/licenses/mit-license.php")
                    }
                }
                developers {
                    developer {
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
                    tag.set("$version")
                }
            }
        }
    }
}

nexusPublishing {
    transitionCheckOptions {
        maxRetries.set(300)
        delayBetween.set(Duration.ofSeconds(10))
    }
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
