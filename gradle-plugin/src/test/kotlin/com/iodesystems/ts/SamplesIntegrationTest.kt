package com.iodesystems.ts

import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

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

    @Ignore("TODO: Re-implement after refactor")
    @Test
    fun `publish plugin to mavenLocal then build sample project`() {
        val root = findRepoRoot()
        val mvnLocal = "-Dmaven.repo.local=${File(System.getProperty("user.dir"), "/../build/m2/repository")}"
        val skipSigning = "-PskipSigning=true"
        runCmd(root, "./gradlew", mvnLocal, skipSigning, "publishToMavenLocal").also { res ->
            assertEquals(0, res.code, "publishToMavenLocal failed. Output:\n${res.output}")
        }
        runCmd(File(root, "samples/spring"), "./gradlew", mvnLocal, "generateTypescript").also { res ->
            assertEquals(0, res.code, "generateTypescript failed. Output:\n${res.output}")
        }
    }
}
