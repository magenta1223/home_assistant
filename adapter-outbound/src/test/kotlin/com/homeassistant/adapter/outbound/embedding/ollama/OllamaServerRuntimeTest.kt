package com.homeassistant.adapter.outbound.embedding.ollama

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaServerRuntimeTest {
    @Test
    fun `managed embedding model stays loaded for the server lifetime`() {
        val environment = ManagedOllamaEmbeddingFactory.environment(
            endpoint = OllamaEndpoint("127.0.0.1", 11435),
            modelsDirectory = Files.createTempDirectory("ollama-models-"),
        )

        assertEquals("-1", environment["OLLAMA_KEEP_ALIVE"])
    }

    @Test
    fun `missing managed executable fails with setup instruction before launch`() {
        val directory = Files.createTempDirectory("missing-ollama")
        try {
            val missingExecutable = directory.resolve("ollama.exe")
            var launched = false
            val runtime = OllamaServerRuntime(
                command = listOf(missingExecutable.toString(), "serve"),
                environment = emptyMap(),
                endpoint = OllamaEndpoint("127.0.0.1", 11435),
                requiredExecutable = missingExecutable,
                enforceFreePort = false,
                processStarter = OllamaProcessStarter { _, _ ->
                    launched = true
                    error("must not launch")
                },
            )

            val failure = assertFailsWith<IllegalStateException> { runtime.start() }

            assertContains(failure.message.orEmpty(), "setupEmbedding")
            assertFalse(launched)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `starts after readiness and stops only its child process`() {
        withFakeOllamaApi { endpoint ->
            var child: Process? = null
            val embedder = OllamaEmbeddingService(endpoint.baseUrl, "test-model")
            val runtime = runtime(
                endpoint = endpoint,
                command = longRunningCommand(),
                embeddingProbe = { embedder.embed("probe") },
                processStarted = { child = it },
            )

            runtime.start()

            assertTrue(runtime.isReady)
            runtime.close()
            assertFalse(runtime.isReady)
            assertFalse(child!!.isAlive)
        }
    }

    @Test
    fun `becomes unhealthy when child process exits unexpectedly`() {
        withFakeOllamaApi { endpoint ->
            val runtime = runtime(
                endpoint = endpoint,
                command = shortRunningCommand(),
            )

            runtime.start()

            eventually { !runtime.isReady }
            assertFalse(runtime.isReady)
            runtime.close()
        }
    }

    @Test
    fun `does not launch when managed port is already occupied`() {
        withFakeOllamaApi { endpoint ->
            var launched = false
            val runtime = OllamaServerRuntime(
                command = longRunningCommand(),
                environment = emptyMap(),
                endpoint = endpoint,
                enforceFreePort = true,
                processStarter = OllamaProcessStarter { command, environment ->
                    launched = true
                    startProcess(command, environment)
                },
            )

            val failure = assertFailsWith<IllegalStateException> { runtime.start() }

            assertContains(failure.message.orEmpty(), "already in use")
            assertFalse(launched)
        }
    }

    @Test
    fun `cleans child process when embedding probe fails`() {
        withFakeOllamaApi { endpoint ->
            var child: Process? = null
            val runtime = runtime(
                endpoint = endpoint,
                command = longRunningCommand(),
                embeddingProbe = { error("model missing") },
                processStarted = { child = it },
            )

            val failure = assertFailsWith<IllegalStateException> { runtime.start() }

            assertContains(failure.message.orEmpty(), "setupEmbedding")
            assertFalse(child!!.isAlive)
            assertFalse(runtime.isReady)
        }
    }

    private fun runtime(
        endpoint: OllamaEndpoint,
        command: List<String>,
        embeddingProbe: (() -> Unit)? = null,
        processStarted: (Process) -> Unit = {},
    ) = OllamaServerRuntime(
        command = command,
        environment = emptyMap(),
        endpoint = endpoint,
        embeddingProbe = embeddingProbe,
        startTimeout = Duration.ofSeconds(5),
        enforceFreePort = false,
        processStarter = OllamaProcessStarter { processCommand, processEnvironment ->
            startProcess(processCommand, processEnvironment).also(processStarted)
        },
    )

    private fun startProcess(command: List<String>, environment: Map<String, String>): Process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment().putAll(environment) }
            .start()

    private fun longRunningCommand() = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "while (\$true) { Start-Sleep -Milliseconds 200 }",
    )

    private fun shortRunningCommand() = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "Start-Sleep -Milliseconds 500",
    )

    private fun eventually(condition: () -> Boolean) {
        repeat(50) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue(condition(), "condition was not satisfied before timeout")
    }

    private inline fun withFakeOllamaApi(block: (OllamaEndpoint) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/version") { exchange ->
            exchange.respond(200, "{\"version\":\"test\"}")
        }
        server.createContext("/api/embed") { exchange ->
            val vector = List(768) { "1.0" }.joinToString(",")
            exchange.respond(200, "{\"embeddings\":[[$vector]]}")
        }
        server.start()
        try {
            block(OllamaEndpoint("127.0.0.1", server.address.port))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
