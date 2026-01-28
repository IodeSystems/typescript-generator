package com.iodesystems.ts

import com.iodesystems.ts.lib.Asserts.assertContains
import com.iodesystems.ts.lib.Asserts.assertNotContains
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@RestController
@RequestMapping("/react-test")
class ReactTestApi {
    @GetMapping
    fun get(): String = "test"
}

class ReactHookTest {

    @Test
    fun `emitReactHelpers generates hook and provider files`() {
        TypeScriptGenerator.build {
            cleanOutputDir()
            includeApi(ReactTestApi::class)
            emitLibAsSeparateFile()
            emitReactHelpers()
            outputDirectory("./build/test/output-test/react-helpers")
        }.generate().write()

        val dir = File("./build/test/output-test/react-helpers")
        val hookFile = File(dir, "use-api.ts")
        val providerFile = File(dir, "api-provider.tsx")
        val libFile = File(dir, "api-lib.ts")

        assertTrue(hookFile.exists(), "Hook file should be generated")
        assertTrue(providerFile.exists(), "Provider file should be generated")
        assertTrue(libFile.exists(), "Lib file should be generated")

        // Verify hook file content (pure TypeScript, no JSX)
        val hookContent = hookFile.readText()
        hookContent.assertContains(
            fragment = "import { createContext, useContext, useMemo } from 'react'",
            why = "Hook should import React dependencies"
        )
        hookContent.assertContains(
            fragment = "import { ApiOptions } from './api-lib'",
            why = "Hook should import ApiOptions from lib file"
        )
        hookContent.assertContains(
            fragment = "export { ApiOptions }",
            why = "Hook should re-export ApiOptions"
        )
        hookContent.assertContains(
            fragment = "export type ApiType<T>",
            why = "Hook should export ApiType"
        )
        hookContent.assertContains(
            fragment = "export const ApiContext",
            why = "Hook should export ApiContext"
        )
        hookContent.assertContains(
            fragment = "export function useApi<T>",
            why = "Hook should export useApi hook"
        )
        hookContent.assertNotContains(
            fragment = "ApiProvider",
            why = "Hook file should not contain provider (that's in the .tsx file)"
        )

        // Verify provider file content (TSX with JSX)
        val providerContent = providerFile.readText()
        providerContent.assertContains(
            fragment = "import { ReactNode, useMemo } from 'react'",
            why = "Provider should import ReactNode and useMemo"
        )
        providerContent.assertContains(
            fragment = "import { ApiContext, ApiOptions } from './use-api'",
            why = "Provider should import from hook file"
        )
        providerContent.assertContains(
            fragment = "export interface ApiProviderProps",
            why = "Provider should export ApiProviderProps"
        )
        providerContent.assertContains(
            fragment = "export function ApiProvider",
            why = "Provider should export ApiProvider component"
        )
        providerContent.assertContains(
            fragment = "<ApiContext.Provider",
            why = "Provider should use JSX"
        )
    }

    @Test
    fun `emitReactHelpers uses custom filenames`() {
        TypeScriptGenerator.build {
            cleanOutputDir()
            includeApi(ReactTestApi::class)
            emitLibAsSeparateFile()
            emitReactHelpers("custom-hook.ts", "custom-provider.tsx")
            outputDirectory("./build/test/output-test/react-helpers-custom")
        }.generate().write()

        val dir = File("./build/test/output-test/react-helpers-custom")
        val hookFile = File(dir, "custom-hook.ts")
        val providerFile = File(dir, "custom-provider.tsx")

        assertTrue(hookFile.exists(), "Custom-named hook file should be generated")
        assertTrue(providerFile.exists(), "Custom-named provider file should be generated")

        // Verify provider imports from custom hook file
        val providerContent = providerFile.readText()
        providerContent.assertContains(
            fragment = "import { ApiContext, ApiOptions } from './custom-hook'",
            why = "Provider should import from custom hook filename"
        )
    }

    @Test
    fun `emitReactHelpers imports from correct lib when unified`() {
        // When no separate lib file is configured, lib is in api.ts
        TypeScriptGenerator.build {
            cleanOutputDir()
            includeApi(ReactTestApi::class)
            emitReactHelpers()
            outputDirectory("./build/test/output-test/react-helpers-unified")
        }.generate().write()

        val dir = File("./build/test/output-test/react-helpers-unified")
        val hookFile = File(dir, "use-api.ts")
        val apiFile = File(dir, "api.ts")

        assertTrue(hookFile.exists(), "Hook file should be generated")
        assertTrue(apiFile.exists(), "API file should be generated")

        val hookContent = hookFile.readText()
        hookContent.assertContains(
            fragment = "import { ApiOptions } from './api'",
            why = "Hook should import from api.ts when lib is not separate"
        )
    }
}
