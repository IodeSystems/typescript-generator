package com.iodesystems.build

import java.io.File

object Release {
  fun generateVersion(currentVersion: String, updateMode: String, overrideVersion: String?): String {
    overrideVersion?.let {
      return it
    }

    val nonSnapshotVersion = currentVersion.removeSuffix("-SNAPSHOT")

    val (oldMajor, oldMinor, oldPatch) = nonSnapshotVersion.split(".").map(String::toInt)
    var (newMajor, newMinor, newPatch) = arrayOf(oldMajor, oldMinor, 0)

    when (updateMode) {
      "major" -> newMajor = (oldMajor + 1).also { newMinor = 0 }
      "minor" -> newMinor = oldMinor + 1
      "dev" -> newPatch = oldPatch + 1
      else -> newPatch = oldPatch + 1
    }
    if (updateMode == "dev" || nonSnapshotVersion != currentVersion) {
      return "$newMajor.$newMinor.$newPatch-SNAPSHOT"
    }
    return "$newMajor.$newMinor.$newPatch"
  }
  fun writeVersion(newVersion: String, oldVersion: String) {
    val buildFile = File("build.gradle.kts")
    val oldContent = buildFile.readText()
    val newContent = oldContent.replace("""= "$oldVersion"""", """= "$newVersion"""")
    buildFile.writeText(newContent)
  }
}
