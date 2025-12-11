package com.iodesystems.ts

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction


class TypeScriptGeneratorPlugin : Plugin<Project> {

    interface Extension {
        var config: Config?

        fun config(f: Config.Builder.() -> Config.Builder) {
            config = Config.Builder(config ?: Config()).f().config
        }
    }

    abstract class Task : DefaultTask() {
        @get:Internal
        var ext: Extension? = null

        @TaskAction
        fun generate() {
            TypeScriptGenerator(ext?.config ?: Config())
                .generate()
                .write()
        }
    }

    override fun apply(target: Project) {
        // Register under canonical name with 'create' so Kotlin DSL accessors are generated
        val ext = target.extensions.create("typescriptGenerator", Extension::class.java)
        // Also register a legacy alias to avoid breaking older build scripts
        target.extensions.add("generateTypescript", ext)
        target.tasks.register("generateTypescript", Task::class.java) { t ->
            // Setup dependencies & grouping
            t.group = BasePlugin.BUILD_GROUP
            target.tasks.getByName("processResources").dependsOn(t)

            // Configure the classpath
            val javaExt = target.extensions.findByType(JavaPluginExtension::class.java)
            val classpathUris: List<String> = if (javaExt != null) {
                val main = javaExt.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                buildList {
                    addAll(main.output.classesDirs.map { it.toURI().toString() })
                    addAll(main.compileClasspath.map { it.toURI().toString() })
                    addAll(main.runtimeClasspath.map { it.toURI().toString() })
                }
            } else emptyList()
            ext.config {
                classPathUrls {
                    it + classpathUris
                }
            }
            t.ext = ext

            // Respect Java compilation tasks as inputs for up-to-date checks
            target.tasks.filter { it.name.startsWith("compileKotlin") || it.name.startsWith("compileJava") }
                .forEach { compileTask ->
                    t.dependsOn(compileTask)
                    t.inputs.files(compileTask)
                }
        }
    }
}