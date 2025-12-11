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

        // 1) ./gradlew publishToMavenLocal
        runCmd(root, "./gradlew", "publishToMavenLocal").also { res ->
            assertEquals(0, res.code, "publishToMavenLocal failed. Output:\n${res.output}")
        }

        val sample = File(root, "samples/spring")
        assertTrue(sample.isDirectory, "samples/spring directory not found at ${sample.absolutePath}")

        // 2) ./gradlew generateTypescript
        runCmd(sample, "./gradlew", "generateTypescript", "-d").also { res ->
            assertEquals(0, res.code, "generateTypescript failed. Output:\n${res.output}")
        }

        // 3) ./gradlew build
        runCmd(sample, "./gradlew", "build").also { res ->
            assertEquals(0, res.code, "build failed. Output:\n${res.output}")
        }
    }
}
