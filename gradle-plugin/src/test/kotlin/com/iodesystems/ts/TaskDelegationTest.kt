package com.iodesystems.ts

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDelegationTest {

    @Test
    fun `task conventions delegate to extension and applyToConfig uses them`() {
        val project = ProjectBuilder.builder().build()
        // Ensure JavaPluginExtension is available (for classpath wiring in plugin)
        project.plugins.apply("java")
        project.plugins.apply(TypeScriptGeneratorPlugin::class.java)

        val ext = project.extensions.getByName("generateTypescript") as TypeScriptGeneratorPlugin.Extension

        // Set a subset of properties on the extension
        ext.outputDirectory.set("./delegated")
        ext.emitLibFileName.set("api-lib.ts")
        ext.includeApiExcludes.set(listOf("Internal.*"))
        ext.typeNameReplacements.set(mapOf("[.$]" to ""))

        val task = project.tasks.getByName("generateTypescript") as TypeScriptGeneratorPlugin.Task
        val appliedFromTask = task.applyToConfig(Config())

        assertEquals(
            "./delegated",
            appliedFromTask.outputDirectory,
            "Task should reflect Extension.outputDirectory via convention"
        )
        assertEquals(
            "api-lib.ts",
            appliedFromTask.emitLibFileName,
            "Task should reflect Extension.emitLibFileName via convention"
        )
        assertEquals(
            listOf("Internal.*"),
            appliedFromTask.includeApiExcludes,
            "Task should reflect Extension.includeApiExcludes via convention"
        )
        assertEquals(
            mapOf("[.$]" to ""),
            appliedFromTask.typeNameReplacements,
            "Task should reflect Extension.typeNameReplacements via convention"
        )

    }
}
