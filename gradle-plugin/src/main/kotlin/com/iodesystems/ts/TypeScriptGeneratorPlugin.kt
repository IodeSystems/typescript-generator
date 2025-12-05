package com.iodesystems.ts

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction


class TypeScriptGeneratorPlugin : Plugin<Project> {

    interface Extension {
        @get:Optional
        @get:Input
        val cleanOutputDir: Property<Boolean>

        @get:Optional
        @get:Input
        val mappedTypes: MapProperty<String, String>

        @get:Optional
        @get:Input
        val classPathUrls: SetProperty<String>

        @get:Optional
        @get:Input
        val outputDirectory: Property<String>

        @get:Optional
        @get:Input
        val basePackages: ListProperty<String>

        @get:Optional
        @get:Input
        val optionalAnnotations: SetProperty<String>

        @get:Optional
        @get:Input
        val nullableAnnotations: SetProperty<String>

        @get:Optional
        @get:Input
        val includeApiIncludes: ListProperty<String>

        @get:Optional
        @get:Input
        val includeApiExcludes: ListProperty<String>

        @get:Optional
        @get:Input
        val typeNameReplacements: MapProperty<String, String>

        // nullable in Config, represented as absent when not set
        @get:Optional
        @get:Input
        val emitLibFileName: Property<String>

        // nullable in Config, represented as absent when not set
        @get:Optional
        @get:Input
        val groupApiFile: MapProperty<String, List<String>>

        @get:Optional
        @get:Input
        val typesFileName: Property<String>

        @get:Optional
        @get:Input
        val externalImportLines: MapProperty<String, String>

        // The Map/Set/List Properties have a convention of being set to empty - dumb.
        fun unsetAll() {
            cleanOutputDir.unset().unsetConvention()
            mappedTypes.unset().unsetConvention()
            classPathUrls.unset().unsetConvention()
            outputDirectory.unset().unsetConvention()
            basePackages.unset().unsetConvention()
            optionalAnnotations.unset().unsetConvention()
            nullableAnnotations.unset().unsetConvention()
            includeApiIncludes.unset().unsetConvention()
            includeApiExcludes.unset().unsetConvention()
            typeNameReplacements.unset().unsetConvention()
            emitLibFileName.unset().unsetConvention()
            groupApiFile.unset().unsetConvention()
            typesFileName.unset().unsetConvention()
            externalImportLines.unset().unsetConvention()
        }

        fun applyTo(other: Extension) {
            if (cleanOutputDir.isPresent) other.cleanOutputDir.set(cleanOutputDir.get())
            if (mappedTypes.isPresent) other.mappedTypes.set(mappedTypes.get())
            if (classPathUrls.isPresent) other.classPathUrls.set(classPathUrls.get())
            if (outputDirectory.isPresent) other.outputDirectory.set(outputDirectory.get())
            if (basePackages.isPresent) other.basePackages.set(basePackages.get())
            if (optionalAnnotations.isPresent) other.optionalAnnotations.set(optionalAnnotations.get())
            if (nullableAnnotations.isPresent) other.nullableAnnotations.set(nullableAnnotations.get())
            if (includeApiIncludes.isPresent) other.includeApiIncludes.set(includeApiIncludes.get())
            if (includeApiExcludes.isPresent) other.includeApiExcludes.set(includeApiExcludes.get())
            if (typeNameReplacements.isPresent) other.typeNameReplacements.set(typeNameReplacements.get())
            if (emitLibFileName.isPresent) other.emitLibFileName.set(emitLibFileName.get())
            if (groupApiFile.isPresent) other.groupApiFile.set(groupApiFile.get())
            if (typesFileName.isPresent) other.typesFileName.set(typesFileName.get())
            if (externalImportLines.isPresent) other.externalImportLines.set(externalImportLines.get())
        }

        fun applyToConfig(config: Config): Config {
            return config.copy(
                outputDirectory = if (outputDirectory.isPresent) outputDirectory.get() else config.outputDirectory,
                classPathUrls = if (classPathUrls.isPresent) classPathUrls.get().toList() else config.classPathUrls,
                cleanOutputDir = if (cleanOutputDir.isPresent) cleanOutputDir.get() else config.cleanOutputDir,
                mappedTypes = if (mappedTypes.isPresent) mappedTypes.get() else config.mappedTypes,
                basePackages = if (basePackages.isPresent) basePackages.get() else config.basePackages,
                optionalAnnotations = if (optionalAnnotations.isPresent) optionalAnnotations.get() else config.optionalAnnotations,
                nullableAnnotations = if (nullableAnnotations.isPresent) nullableAnnotations.get() else config.nullableAnnotations,
                includeApiIncludes = if (includeApiIncludes.isPresent) includeApiIncludes.get() else config.includeApiIncludes,
                includeApiExcludes = if (includeApiExcludes.isPresent) includeApiExcludes.get() else config.includeApiExcludes,
                typeNameReplacements = if (typeNameReplacements.isPresent) typeNameReplacements.get() else config.typeNameReplacements,
                emitLibFileName = if (emitLibFileName.isPresent) emitLibFileName.get() else config.emitLibFileName,
                groupApiFile = if (groupApiFile.isPresent) groupApiFile.get() else config.groupApiFile,
                typesFileName = if (typesFileName.isPresent) typesFileName.get() else config.typesFileName,
                externalImportLines = if (externalImportLines.isPresent) externalImportLines.get() else config.externalImportLines,
            )
        }
    }

    abstract class Task : DefaultTask(), Extension {
        @TaskAction
        fun generate() {
            TypeScriptGenerator.build {
                Config.Builder(applyToConfig(it.config))
            }
                .generate()
                .write()

        }
    }

    override fun apply(target: Project) {
        target.extensions.create("generateTypescript", Extension::class.java).unsetAll()
        target.tasks.register("generateTypescript", Task::class.java) { t ->
            t.unsetAll()
            t.group = BasePlugin.BUILD_GROUP
            target.tasks.getByName("processResources").dependsOn(t)
            target.tasks.filter { it.name.startsWith("compileKotlin") || it.name.startsWith("compileJava") }
                .forEach { compileTask ->
                    t.dependsOn(compileTask)
                    t.inputs.files(compileTask)
                }

            val ext = target.extensions.getByType(Extension::class.java)
            ext.applyTo(t)
            val javaExt = target.extensions.findByType(JavaPluginExtension::class.java)
            if (javaExt != null) {
                val main = javaExt.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
                // Local Api's
                t.classPathUrls.addAll(main.output.classesDirs.map { it.toURI().toString() })
                // Api's from dependencies
                t.classPathUrls.addAll(main.compileClasspath.map { it.toURI().toString() })
            }
        }
    }
}