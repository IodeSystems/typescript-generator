package com.iodesystems.ts.sample

import com.microsoft.playwright.Playwright
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertNotNull

class E2eHarnessTest {

    @Test
    fun harness_starts_spring_and_vite_and_can_open_with_playwright() {
        // Pick free ports
        val springPort = findFreePort()
        val vitePort = findFreePort()

        var springContext: ConfigurableApplicationContext? = null
        var viteProcess: Process? = null
        try {
            springContext = SpringApplication.run(
                Spring::class.java,
                "--server.port=$springPort"
            )

            // Wait for Spring readiness (responding on HTTP)
            waitForHttp(
                "http://localhost:$springPort/actuator/health",
                fallbackUrl = "http://localhost:$springPort/",
                maxWaitSeconds = 60
            )

            // Start Vite dev server via node
            val projectRoot = Paths.get("").toAbsolutePath().normalize().toFile()
            val nodeCmd = "node"
            val viteJs = projectRoot.toPath().resolve("node_modules/vite/bin/vite.js").toFile()
            val pb = ProcessBuilder(
                nodeCmd,
                viteJs.absolutePath,
                "--port",
                vitePort.toString(),
                "--strictPort",
                "--host",
                "127.0.0.1"
            )
            pb.directory(projectRoot)
            pb.redirectErrorStream(true)
            viteProcess = pb.start()

            // Optionally print some startup logs to help debugging locally
            Thread {
                try {
                    BufferedReader(InputStreamReader(viteProcess!!.inputStream)).use { br ->
                        var line: String?
                        while (br.readLine().also { line = it } != null) {
                            if (!line.isNullOrBlank()) {
                                println("[DEBUG_LOG][Vite] $line")
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()

            // Wait until Vite responds
            waitForHttp("http://127.0.0.1:$vitePort/", maxWaitSeconds = 90)

            // Use Playwright to open the page and run a simple ping -> pong flow
            Playwright.create().use { pw ->
                pw.chromium().launch().use { browser ->
                    val page = browser.newPage()
                    val response = page.navigate("http://127.0.0.1:$vitePort/?api=http://127.0.0.1:$springPort")
                    assertNotNull(response, "Expected a response from Vite dev server")
                    // Minimal harness check: we reached the dev server (200-404 acceptable)
                    println("[DEBUG_LOG] Navigated to Vite. Status=${response.status()} URL=${response.url()} ")

                    // Click Ping button and expect pong text to render
                    page.waitForSelector("[data-testid=ping-button]")
                    page.click("[data-testid=ping-button]")
                    // Wait for pong to appear
                    page.waitForSelector("[data-testid=pong-text]:has-text(\"pong\")")
                    println("[DEBUG_LOG] Ping/Pong flow succeeded")
                }
            }
        } finally {
            try {
                viteProcess?.destroy(); viteProcess?.waitFor()
            } catch (_: Exception) {
            }
            try {
                springContext?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
            socket.reuseAddress = true
            return (socket.localSocketAddress as InetSocketAddress).port
        }
    }

    private fun waitForHttp(url: String, fallbackUrl: String? = null, maxWaitSeconds: Long = 60) {
        val start = Instant.now()
        var lastError: Exception? = null
        val tryUrls = if (fallbackUrl != null) listOf(url, fallbackUrl) else listOf(url)
        while (Duration.between(start, Instant.now()).seconds < maxWaitSeconds) {
            for (u in tryUrls) {
                try {
                    val conn = URI.create(u).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true
                    val code = conn.responseCode
                    if (code in 200..499) {
                        return
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            Thread.sleep(250)
        }
        throw IllegalStateException("Timed out waiting for $url. Last error=${lastError?.message}")
    }
}
