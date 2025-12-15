package com.iodesystems.ts

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.Asserts.assertNotContains
import com.iodesystems.ts.lib.TestUtils.emitter
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.test.Test

/**
 * Tests that generic type parameters are not included in import statements.
 * Bug: import { DataSetResponse<T> } from './api-types' - the <T> should not be there
 */
class GenericImportTest {

    data class DataSetResponse<T>(
        val data: List<T>,
        val total: Int
    )

    data class User(
        val id: Int,
        val name: String
    )

    @RestController
    @RequestMapping("/api/generic")
    class GenericResponseController {
        @PostMapping("/users")
        fun getUsers(): DataSetResponse<User> = error("stub")
    }

    @Test
    fun `import statements should not contain generic type parameters`() {
        val em = emitter(GenericResponseController::class) {
            typesFileName("api-types.ts")
            emitLibAsSeparateFile()
        }

        val output = em.ts()

        // Find the api.ts file content
        val apiFile = output.files.find { it.file.name == "api.ts" }
        val apiContent = apiFile?.content?.toString() ?: ""

        // The import should NOT contain <T> or any other generic parameters
        apiContent.assertNotContains(
            fragment = "<T>",
            why = "Import statements should not contain generic type parameters like <T>"
        )

        // The import should contain the type names without generics
        apiContent.assertContains(
            fragment = "import {",
            why = "There should be an import statement"
        )

        // Verify the types file contains the correct generic type definition
        val typesFile = output.files.find { it.file.name == "api-types.ts" }
        val typesContent = typesFile?.content?.toString() ?: ""

        typesContent.assertContains(
            fragment = "GenericImportTestDataSetResponse<T>",
            why = "The types file should define the generic type with its parameter"
        )
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.SIMPLE_NAME)
    sealed interface Result<T> {
        val value: T

        data class Ok<T>(override val value: T) : Result<T>
        data class Err<T>(override val value: T, val message: String) : Result<T>
    }

    @RestController
    @RequestMapping("/api/generic-union")
    class GenericUnionController {
        @PostMapping("/result")
        fun getResult(): Result<String> = error("stub")
    }

    @Test
    fun `import statements for generic unions should not contain type parameters`() {
        val em = emitter(GenericUnionController::class) {
            typesFileName("api-types.ts")
            emitLibAsSeparateFile()
        }

        val output = em.ts()

        // Find the api.ts file content
        val apiFile = output.files.find { it.file.name == "api.ts" }
        val apiContent = apiFile?.content?.toString() ?: ""

        // Extract only the import lines for checking
        val importLines = apiContent.lines().filter { it.startsWith("import") }
        val importSection = importLines.joinToString("\n")

        // The import lines should NOT contain <T> or any other generic parameters
        importSection.assertNotContains(
            fragment = "<T>",
            why = "Import statements should not contain generic type parameters like <T>"
        )

        importSection.assertNotContains(
            fragment = "<string>",
            why = "Import statements should not contain resolved generic parameters"
        )

        // Verify the method signature correctly uses the resolved generic type (this is valid TypeScript)
        apiContent.assertContains(
            fragment = "GenericImportTestResultUnion<string>",
            why = "Method signatures should use resolved generic types"
        )
    }
}
