import com.iodesystems.build.Bash.bash
import com.iodesystems.build.Release
import java.time.Duration

plugins {
    kotlin("jvm")
    `java-library`
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin")
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

tasks.register("versionGet") {
    group = "release"
    val version = version
    doLast {
        println(version)
    }
}
tasks.register("versionSet") {
    group = "release"
    val overrideVersion = properties["overrideVersion"]
    val updateMode = (properties["updateMode"] ?: "patch").toString()
    val version = properties["version"]!!.toString()
    doLast {
        val newVersion = Release.generateVersion(version, updateMode, overrideVersion?.toString())
        Release.writeVersion(newVersion, version)
    }
}


tasks.register("releaseStripSnapshotCommitAndTag") {
    dependsOn(tasks.test)
    group = "release"
    val version = properties["version"]!!.toString()
    doLast {
        val status = "git status --porcelain".bash().output.trim()
        if (status.isNotEmpty()) throw GradleException("There are changes in the working directory:\n$status")
        val oldVersion = version
        val newVersion = oldVersion.removeSuffix("-SNAPSHOT")
        Release.writeVersion(newVersion, oldVersion)
        "git add .".bash()
        "git commit -m 'Release $newVersion'".bash()
        "git tag -a v$newVersion -m 'Release $newVersion'".bash()
    }
}
tasks.register("releaseRevert") {
    group = "release"
    val version = properties["version"]!!.toString()
    doLast {
        val oldVersion = version
        val newVersion = "$oldVersion-SNAPSHOT"
        Release.writeVersion(newVersion, oldVersion)
        "git reset --hard HEAD~1".bash()
        "git tag -d v$oldVersion".bash()
        println("Reverted to $newVersion")
    }
}
tasks.register("releasePublish") {
    group = "release"
    dependsOn(
        tasks.clean, tasks.build,
        subprojects.map { project -> project.tasks.publish },
        tasks.closeAndReleaseStagingRepositories
    )
}
tasks.register("releasePrepareNextDevelopmentIteration") {
    group = "release"
    val overrideVersion = properties["overrideVersion"]?.toString()
    val version = properties["version"]!!.toString()
    doLast {
        val oldVersion = version
        val newVersion = Release.generateVersion(version, "dev", overrideVersion)
        Release.writeVersion(newVersion, oldVersion)
        "git add .".bash()
        "git commit -m 'Prepare next development iteration: $newVersion'".bash()
        "git push".bash()
    }
}
