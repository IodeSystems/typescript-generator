package com.iodesystems.ts

import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

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
                .excludeApis("Internal.*")
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
            built.includeApiExcludes,
            "Config built from extension lambda should reflect includeApiExcludes"
        )
        assertEquals(
            mapOf("[.$]" to ""),
            built.typeNameReplacements,
            "Config built from extension lambda should reflect typeNameReplacements"
        )

    }
}
