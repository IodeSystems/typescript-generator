package com.iodesystems.ts

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskAction


class TypeScriptGeneratorPlugin : Plugin<Project> {

    interface Extension {
        // Retained for backwards compatibility with existing build scripts
        var config: Config?

        fun config(f: Config.Builder.() -> Unit) {
            val b = Config.Builder(config ?: Config())
            b.f()
            config = b.config
        }
    }

    abstract class Task : DefaultTask() {
        // All configuration properties declared with proper Gradle property types
        // for configuration cache compatibility

        @get:Input
        abstract val includeRefComments: Property<Boolean>

        @get:Input
        abstract val cleanOutputDir: Property<Boolean>

        @get:Input
        abstract val setsAsArrays: Property<Boolean>

        @get:Input
        abstract val exclude: ListProperty<String>

        @get:Input
        abstract val mapType: MapProperty<String, String>

        @get:Input
        abstract val alias: MapProperty<String, String>

        @get:OutputDirectory
        abstract val outputDirectory: DirectoryProperty

        @get:Input
        abstract val optionalAnnotations: SetProperty<String>

        @get:Input
        abstract val nullableAnnotations: SetProperty<String>

        @get:Input
        abstract val packageScan: ListProperty<String>

        @get:Input
        abstract val packageIgnore: ListProperty<String>

        @get:Input
        abstract val typeNameReplacements: MapProperty<String, String>

        @get:Input
        @get:Optional
        abstract val emitLibFileName: Property<String>

        @get:Input
        @get:Optional
        abstract val groupApiFile: MapProperty<String, List<String>>

        @get:Input
        @get:Optional
        abstract val typesFileName: Property<String>

        @get:Input
        abstract val externalImportLines: MapProperty<String, String>

        @get:Input
        abstract val headerLines: ListProperty<String>

        @get:Input
        abstract val diagnosticLogging: Property<Boolean>

        @get:Input
        abstract val include: ListProperty<String>

        @get:Input
        abstract val autoDetectIsGetters: Property<Boolean>

        @get:Input
        abstract val allowIsGettersForNonBoolean: Property<Boolean>

        @get:Input
        abstract val useStdBeanNaming: Property<Boolean>

        @get:Input
        abstract val classPathUrls: ListProperty<String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val compiledClasses: ConfigurableFileCollection

        @TaskAction
        fun generate() {
            val config = Config(
                includeRefComments = includeRefComments.get(),
                cleanOutputDir = cleanOutputDir.get(),
                classPathUrls = classPathUrls.get(),
                setsAsArrays = setsAsArrays.get(),
                exclude = exclude.get(),
                mapType = mapType.get(),
                alias = alias.get(),
                outputDirectory = outputDirectory.get().asFile.absolutePath,
                optionalAnnotations = optionalAnnotations.get(),
                nullableAnnotations = nullableAnnotations.get(),
                packageScan = packageScan.get(),
                packageIgnore = packageIgnore.get(),
                typeNameReplacements = typeNameReplacements.get(),
                emitLibFileName = emitLibFileName.orNull,
                groupApiFile = groupApiFile.orNull?.takeIf { it.isNotEmpty() },
                typesFileName = typesFileName.orNull,
                externalImportLines = externalImportLines.get(),
                headerLines = headerLines.get(),
                diagnosticLogging = diagnosticLogging.get(),
                include = include.get(),
                autoDetectIsGetters = autoDetectIsGetters.get(),
                allowIsGettersForNonBoolean = allowIsGettersForNonBoolean.get(),
                useStdBeanNaming = useStdBeanNaming.get(),
            )
            TypeScriptGenerator(config)
                .generate()
                .write()
        }
    }

    override fun apply(target: Project) {
        // Register under canonical name with 'create' so Kotlin DSL accessors are generated
        val ext = target.extensions.create("typescriptGenerator", Extension::class.java)
        // Also register a legacy alias to avoid breaking older build scripts
        target.extensions.add("generateTypescript", ext)

        // Create a provider that lazily reads the extension config
        // This defers reading until task execution, after the build script has configured the extension
        val defaults = Config()
        val configProvider: Provider<Config> = target.provider { ext.config ?: defaults }

        val taskProvider = target.tasks.register("generateTypescript", Task::class.java) { t ->
            t.group = BasePlugin.BUILD_GROUP

            // Use providers that derive values from the extension config lazily
            t.includeRefComments.convention(configProvider.map { it.includeRefComments })
            t.cleanOutputDir.convention(configProvider.map { it.cleanOutputDir })
            t.setsAsArrays.convention(configProvider.map { it.setsAsArrays })
            t.exclude.convention(configProvider.map { it.exclude })
            t.mapType.convention(configProvider.map { it.mapType })
            t.alias.convention(configProvider.map { it.alias })
            t.outputDirectory.convention(target.layout.projectDirectory.dir(configProvider.map { it.outputDirectory }))
            t.optionalAnnotations.convention(configProvider.map { it.optionalAnnotations })
            t.nullableAnnotations.convention(configProvider.map { it.nullableAnnotations })
            t.packageScan.convention(configProvider.map { it.packageScan })
            t.packageIgnore.convention(configProvider.map { it.packageIgnore })
            t.typeNameReplacements.convention(configProvider.map { it.typeNameReplacements })
            t.emitLibFileName.convention(configProvider.map { it.emitLibFileName ?: "" }.map { it.ifEmpty { null } })
            t.groupApiFile.convention(configProvider.map { it.groupApiFile ?: emptyMap() })
            t.typesFileName.convention(configProvider.map { it.typesFileName ?: "" }.map { it.ifEmpty { null } })
            t.externalImportLines.convention(configProvider.map { it.externalImportLines })
            t.headerLines.convention(configProvider.map { it.headerLines })
            t.diagnosticLogging.convention(configProvider.map { it.diagnosticLogging })
            t.include.convention(configProvider.map { it.include })
            t.autoDetectIsGetters.convention(configProvider.map { it.autoDetectIsGetters })
            t.allowIsGettersForNonBoolean.convention(configProvider.map { it.allowIsGettersForNonBoolean })
            t.useStdBeanNaming.convention(configProvider.map { it.useStdBeanNaming })

            // Wire classpath from Java plugin if available, using lazy providers
            val javaExt = target.extensions.findByType(JavaPluginExtension::class.java)
            if (javaExt != null) {
                val mainSourceSet = javaExt.sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME)

                // Create a lazy classpath provider that merges extension URLs with Java plugin classpath
                val classpathProvider = configProvider.zip(mainSourceSet) { config, main ->
                    buildList {
                        addAll(config.classPathUrls)
                        addAll(main.output.classesDirs.map { it.toURI().toString() })
                        addAll(main.compileClasspath.map { it.toURI().toString() })
                        addAll(main.runtimeClasspath.map { it.toURI().toString() })
                    }
                }
                t.classPathUrls.convention(classpathProvider)

                // Wire up compiled classes as inputs for up-to-date checks
                t.compiledClasses.from(mainSourceSet.map { it.output.classesDirs })
                t.compiledClasses.from(mainSourceSet.map { it.compileClasspath })
            } else {
                // No Java plugin - just use extension config classpath
                t.classPathUrls.convention(configProvider.map { it.classPathUrls })
            }
        }

        // Wire task dependencies lazily using task matching
        target.plugins.withId("java") {
            target.tasks.named("processResources") { it.dependsOn(taskProvider) }
        }

        // Add compile task dependencies using task matching (configuration cache compatible)
        taskProvider.configure { t ->
            t.dependsOn(target.tasks.matching {
                it.name.startsWith("compileKotlin") || it.name.startsWith("compileJava")
            })
        }
    }
}