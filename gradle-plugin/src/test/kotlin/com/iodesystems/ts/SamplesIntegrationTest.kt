package com.iodesystems.ts

import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplesIntegrationTest {

    private fun findRepoRoot(start: File = File(".").absoluteFile.normalize()): File {
        var cur: File? = start
        while (cur != null) {
            if (File(cur, "settings.gradle.kts").exists()) return cur
            cur = cur.parentFile
        }
        error("Could not locate repository root from ${start}")
    }

    data class CmdResult(val code: Int, val output: String)

    private fun runCmd(workingDir: File, vararg command: String, timeoutSeconds: Long = 20 * 60): CmdResult {
        val pb = ProcessBuilder(*command)
            .directory(workingDir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader()
        val sb = StringBuilder()
        var line: String?
        while (true) {
            line = out.readLine() ?: break
            sb.appendLine(line)
            // Echo to test output for easier debugging
            println(line)
        }
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return CmdResult(-1, sb.toString() + "\n[TIMEOUT]\n")
        }
        return CmdResult(proc.exitValue(), sb.toString())
    }

    @Test
    fun `publish plugin to mavenLocal then build sample project`() {
        val root = findRepoRoot()
        val mvnLocal = "-Dmaven.repo.local=${File(System.getProperty("user.dir"), "/../build/m2/repository")}"
        val skipSigning = "-PskipSigning=true"
        runCmd(root, "./gradlew", mvnLocal, skipSigning, "publishToMavenLocal").also { res ->
            assertEquals(0, res.code, "publishToMavenLocal failed. Output:\n${res.output}")
        }
        runCmd(
            File(root, "samples/spring"),
            "./gradlew",
            mvnLocal,
            "generateTypescript",
            "--configuration-cache"
        ).also { res ->
            assertEquals(0, res.code, "generateTypescript failed. Output:\n${res.output}")
        }

        // Verify the generated output
        val apiFile = File(root, "samples/spring/src/main/ui/gen/api.ts")
        assertTrue(apiFile.exists(), "Generated api.ts file should exist")
        val apiContent = apiFile.readText()

        // Verify @RequestBody parameters are correctly detected
        // The add() and ping() methods should have request body parameters
        assertTrue(
            apiContent.contains("add(req:") || apiContent.contains("add(req?:"),
            "add() method should have a request body parameter 'req'. Got:\n$apiContent"
        )
        assertTrue(
            apiContent.contains("ping(req:") || apiContent.contains("ping(req?:"),
            "ping() method should have a request body parameter 'req'. Got:\n$apiContent"
        )

        // Verify body is being sent in fetch calls
        assertTrue(
            apiContent.contains("body: JSON.stringify(req)"),
            "Request body should be JSON stringified. Got:\n$apiContent"
        )

        // Verify nested sealed interface types are correctly generated
        // Types are in api-types.ts
        val typesFile = File(root, "samples/spring/src/main/ui/gen/api-types.ts")
        assertTrue(typesFile.exists(), "Generated api-types.ts file should exist")
        val typesContent = typesFile.readText()

        // RefUnion should contain RefOrg, RefBu, RefLoc - NOT SlugRef types
        val refUnionLine = typesContent.lines().find { it.contains("RefUnion =") && !it.contains("SlugRefUnion") }
        assertTrue(refUnionLine != null, "RefUnion should be defined. Types:\n$typesContent")
        assertTrue(
            refUnionLine!!.contains("RefOrg") && refUnionLine.contains("RefBu") && refUnionLine.contains("RefLoc"),
            "RefUnion should contain RefOrg, RefBu, RefLoc. Got: $refUnionLine"
        )
        assertTrue(
            !refUnionLine.contains("SlugRef"),
            "RefUnion should NOT contain SlugRef types. Got: $refUnionLine"
        )

        // SlugRefUnion should contain SlugRefOrg, SlugRefBu, SlugRefLoc
        val slugRefUnionLine = typesContent.lines().find { it.contains("SlugRefUnion =") }
        assertTrue(slugRefUnionLine != null, "SlugRefUnion should be defined")
        assertTrue(
            slugRefUnionLine!!.contains("SlugRefOrg") && slugRefUnionLine.contains("SlugRefBu") && slugRefUnionLine.contains("SlugRefLoc"),
            "SlugRefUnion should contain SlugRefOrg, SlugRefBu, SlugRefLoc. Got: $slugRefUnionLine"
        )
    }
}
