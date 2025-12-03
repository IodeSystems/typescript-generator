package com.iodesystems.ts

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.net.URL


class TypeScriptGeneratorPlugin : Plugin<Project> {

    abstract class Extension {
        // This is where we configure the settings (should be a good mapping from Config to here)
        abstract val mappedTypes: Property<Map<String, String>>
    }

    open class Task : DefaultTask() {

        // This is how we bind the classpath to the scanner
        @Input
        val classPath: MutableList<URL> = mutableListOf()

        @Input
        var extension: Extension? = null

        @TaskAction
        fun generate() {
            println("Generating typescript generator")
            println(classPath.joinToString("\n"))
            val output = TypeScriptGenerator.build {
                it.basePackages()
            }.generate()
            output.write()
        }
    }


    override fun apply(target: Project) {
        val ext = target.extensions.create("generateTypescript", Extension::class.java)
        val task = target.tasks.register("generateTypescript", Task::class.java).get()
        task.group = BasePlugin.BUILD_GROUP
        target.tasks.getByName("processResources").dependsOn(task)
        target.tasks.filter { it.name.startsWith("compileKotlin") || it.name.startsWith("compileJava") }
            .forEach { compileTask ->
                task.dependsOn(compileTask)
                task.inputs.files(compileTask);
            }

        target.afterEvaluate {
            task.extension = ext
            task.classPath.addAll(
                target.configurations.getAt("compileClasspath").files.mapNotNull { file ->
                    file.toURI().toURL()
                }
            )

        }
    }
}