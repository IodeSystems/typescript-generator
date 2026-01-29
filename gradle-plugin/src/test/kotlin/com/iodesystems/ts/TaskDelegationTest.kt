package com.iodesystems.ts

import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.testfixtures.ProjectBuilder
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDelegationTest {

    @Test
    fun `extension config lambda is used to build Config as task would`() {
        val project = ProjectBuilder.builder().build()
        // Ensure JavaPluginExtension is available (for classpath wiring in plugin)
        project.plugins.apply("java")
        project.plugins.apply(TypeScriptGeneratorPlugin::class.java)

        val ext = project.extensions.getByName("typescriptGenerator") as TypeScriptGeneratorPlugin.Extension

        // Provide a builder that sets a subset of Config fields
        ext.config {
            outputDirectory("./delegated")
                .emitLibAsSeparateFile("api-lib.ts")
                .packageIgnore("Internal.*")
                .typeNameReplacements(mapOf("[.$]" to ""))
        }

        // Build Config the same way Task.generate() does (prior to adding classpath URLs)
        val built = ext.config!!

        assertEquals(
            "./delegated",
            built.outputDirectory,
            "Config built from extension lambda should reflect outputDirectory"
        )
        assertEquals(
            "api-lib.ts",
            built.emitLibFileName,
            "Config built from extension lambda should reflect emitLibFileName"
        )
        assertEquals(
            listOf("Internal.*"),
            built.packageIgnore,
            "Config built from extension lambda should reflect packageIgnore"
        )
        assertEquals(
            mapOf("[.$]" to ""),
            built.typeNameReplacements,
            "Config built from extension lambda should reflect typeNameReplacements"
        )

    }

    @Test
    fun `Plugin Task exposes all Config properties`() {
        // Get all Config data class constructor parameters (the actual configuration options)
        val configProps = Config::class.constructors.first().parameters
            .mapNotNull { it.name }
            .toSet()

        // Get all Task properties that are marked as Gradle task inputs/outputs
        val taskProps = TypeScriptGeneratorPlugin.Task::class.memberProperties
            .filter { prop ->
                prop.getter.annotations.any { it is Input || it is Optional || it is OutputDirectory }
            }
            .map { it.name }
            .toSet()

        // These are intentionally handled differently in the plugin
        val excluded = setOf<String>(
            // None currently - all Config constructor params should be exposed
        )

        val missing = configProps - taskProps - excluded

        assertTrue(
            missing.isEmpty(),
            "Config properties missing from Plugin Task: $missing\n" +
                    "All Config properties must be exposed as @Input properties in the Task class " +
                    "to ensure Gradle plugin users can configure them."
        )
    }
}
