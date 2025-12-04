package com.iodesystems.ts

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.testfixtures.ProjectBuilder
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaGetter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionTest {

    val extProps by lazy {
        val configCtorParams = Config::class.constructors.first { it.parameters.isNotEmpty() }.parameters
        val excluded = setOf("includeApiPredicate", "customNamingFunction")
        val expectedNames = configCtorParams
            .map { it.name!! }
            .filter { it !in excluded }
            .toSet()
        val extProps = TypeScriptGeneratorPlugin.Extension::class.declaredMemberProperties
            .associateBy { it.name }

        for (n in expectedNames) {
            assertTrue(extProps.containsKey(n), "Extension should have a property named '$n' to mirror Config.$n")
        }
        for (n in extProps) {
            assertTrue(expectedNames.contains(n.key), "Config should have a property named '$n' to mirror Extension.$n")
        }
        extProps
    }

    @Test
    fun `extension covers config constructor fields and are Property-wrapped with @Input`() {
        // and annotated with @Input on getter
        for ((name, prop) in extProps) {
            val retType = prop.returnType.toString()
            assertTrue(
                retType.contains("Property"),
                "Extension.$name should be a Gradle Property/ListProperty/SetProperty/MapProperty, but was $retType"
            )
            val getter = prop.javaGetter
            assertNotNull(getter, "Extension.$name should have a getter")
            val input = prop.getter.findAnnotation<Input>() ?: getter.getAnnotation(Input::class.java)
            assertNotNull(input, "Extension.$name should be annotated with @Input on the getter")
        }
    }


    @Test
    fun `extension properties are default unset`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "generateTypescriptTest",
            TypeScriptGeneratorPlugin.Extension::class.java
        )
        ext.unsetAll()
        extProps.forEach { (key, value) ->
            val prop = value.get(ext) as Provider<*>
            assertTrue(!prop.isPresent, "Property $key should not be present")
        }
    }


    @Test
    fun `extension properties are gettable and settable`() {
        val project = ProjectBuilder.builder().build()
        val ext = project.extensions.create(
            "generateTypescriptTest",
            TypeScriptGeneratorPlugin.Extension::class.java
        )

        val mapped = mapOf("java.time.OffsetDateTime" to "string")
        val outDir = "./out"
        val base = listOf("com.example")
        val optional = setOf("jakarta.annotation.Nullable")
        val nullable = setOf("org.jetbrains.annotations.Nullable")
        val inc = listOf(".*Api$")
        val exc = listOf("Internal.*")
        val repl = mapOf("[.$]" to "")
        val emitLib = "api-lib.ts"
        val group = mapOf("a.ts" to listOf("com.example.A"))
        val typesName = "api-types.ts"
        val extType = mapOf("Dayjs" to "dayjs")
        val extLines = mapOf("Dayjs" to "import Dayjs from 'dayjs'")

        ext.mappedTypes.set(mapped)
        ext.outputDirectory.set(outDir)
        ext.basePackages.set(base)
        ext.optionalAnnotations.set(optional)
        ext.nullableAnnotations.set(nullable)
        ext.includeApiIncludes.set(inc)
        ext.includeApiExcludes.set(exc)
        ext.typeNameReplacements.set(repl)
        ext.emitLibFileName.set(emitLib)
        ext.groupApiFile.set(group)
        ext.typesFileName.set(typesName)
        ext.externalImportLines.set(extLines)

        assertEquals(mapped, ext.mappedTypes.get(), "mappedTypes should round-trip")
        assertEquals(outDir, ext.outputDirectory.get(), "outputDirectory should round-trip")
        assertEquals(base, ext.basePackages.get(), "basePackages should round-trip")
        assertEquals(optional, ext.optionalAnnotations.get(), "optionalAnnotations should round-trip")
        assertEquals(nullable, ext.nullableAnnotations.get(), "nullableAnnotations should round-trip")
        assertEquals(inc, ext.includeApiIncludes.get(), "includeApiIncludes should round-trip")
        assertEquals(exc, ext.includeApiExcludes.get(), "includeApiExcludes should round-trip")
        assertEquals(repl, ext.typeNameReplacements.get(), "typeNameReplacements should round-trip")
        assertEquals(emitLib, ext.emitLibFileName.get(), "emitLibFileName should round-trip when set")
        assertEquals(group, ext.groupApiFile.get(), "groupApiFile should round-trip when set")
        assertEquals(typesName, ext.typesFileName.get(), "typesFileName should round-trip")
        assertEquals(extLines, ext.externalImportLines.get(), "externalImportLines should round-trip")

        // Test applyTo functionality
        val targetExt = project.extensions.create(
            "generateTypescriptTarget",
            TypeScriptGeneratorPlugin.Extension::class.java
        )
        ext.applyTo(targetExt)

        assertEquals(mapped, targetExt.mappedTypes.get(), "mappedTypes should be copied")
        assertEquals(outDir, targetExt.outputDirectory.get(), "outputDirectory should be copied")
        assertEquals(base, targetExt.basePackages.get(), "basePackages should be copied")
        assertEquals(optional, targetExt.optionalAnnotations.get(), "optionalAnnotations should be copied")
        assertEquals(nullable, targetExt.nullableAnnotations.get(), "nullableAnnotations should be copied")
        assertEquals(inc, targetExt.includeApiIncludes.get(), "includeApiIncludes should be copied")
        assertEquals(exc, targetExt.includeApiExcludes.get(), "includeApiExcludes should be copied")
        assertEquals(repl, targetExt.typeNameReplacements.get(), "typeNameReplacements should be copied")
        assertEquals(emitLib, targetExt.emitLibFileName.get(), "emitLibFileName should be copied")
        assertEquals(group, targetExt.groupApiFile.get(), "groupApiFile should be copied")
        assertEquals(typesName, targetExt.typesFileName.get(), "typesFileName should be copied")
        assertEquals(extLines, targetExt.externalImportLines.get(), "externalImportLines should be copied")

    }
}
