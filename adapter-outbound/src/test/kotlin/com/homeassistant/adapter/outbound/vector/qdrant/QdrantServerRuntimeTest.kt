package com.homeassistant.adapter.outbound.vector.qdrant

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QdrantServerRuntimeTest {
    @Test
    fun `missing managed executable fails with setup instruction before launch`() {
        withTempDirectory("missing-qdrant") { directory ->
            val missingExecutable = directory.resolve("qdrant.exe")
            var launched = false
            val runtime = QdrantServerRuntime(
                command = listOf(missingExecutable.toString()),
                environment = emptyMap(),
                workingDirectory = directory,
                endpoint = QdrantEndpoint("127.0.0.1", 6333),
                requiredExecutable = missingExecutable,
                enforceFreePort = false,
                processStarter = QdrantProcessStarter { _, _, _ ->
                    launched = true
                    error("must not launch")
                },
            )

            val failure = assertFailsWith<IllegalStateException> { runtime.start() }

            assertContains(failure.message.orEmpty(), "setupQdrant")
            assertFalse(launched)
        }
    }

    @Test
    fun `starts after readiness and stops only its child process`() {
        withFakeQdrantApi { endpoint ->
            withTempDirectory("qdrant-runtime") { directory ->
                var child: Process? = null
                val runtime = runtime(endpoint, directory) { child = it }

                runtime.start()

                assertTrue(runtime.isReady)
                runtime.close()
                assertFalse(runtime.isReady)
                assertFalse(child!!.isAlive)
            }
        }
    }

    @Test
    fun `does not launch when managed port is already occupied`() {
        withFakeQdrantApi { endpoint ->
            withTempDirectory("qdrant-port") { directory ->
                var launched = false
                val runtime = QdrantServerRuntime(
                    command = longRunningCommand(),
                    environment = emptyMap(),
                    workingDirectory = directory,
                    endpoint = endpoint,
                    enforceFreePort = true,
                    processStarter = QdrantProcessStarter { _, _, _ ->
                        launched = true
                        error("must not launch")
                    },
                )

                val failure = assertFailsWith<IllegalStateException> { runtime.start() }

                assertContains(failure.message.orEmpty(), "already in use")
                assertFalse(launched)
            }
        }
    }

    @Test
    fun `rejects non-local or path-based endpoints`() {
        assertFailsWith<IllegalArgumentException> { QdrantEndpoint.parse("http://example.com:6333") }
        assertFailsWith<IllegalArgumentException> { QdrantEndpoint.parse("http://127.0.0.1:6333/qdrant") }
        assertFailsWith<IllegalArgumentException> { QdrantEndpoint.parse("https://127.0.0.1:6333") }
    }

    private fun runtime(
        endpoint: QdrantEndpoint,
        directory: Path,
        processStarted: (Process) -> Unit,
    ) = QdrantServerRuntime(
        command = longRunningCommand(),
        environment = emptyMap(),
        workingDirectory = directory,
        endpoint = endpoint,
        startTimeout = Duration.ofSeconds(5),
        enforceFreePort = false,
        processStarter = QdrantProcessStarter { command, environment, workingDirectory ->
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .also { it.environment().putAll(environment) }
                .start()
                .also(processStarted)
        },
    )

    private fun longRunningCommand() = listOf(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        "while (\$true) { Start-Sleep -Milliseconds 200 }",
    )

    private inline fun withFakeQdrantApi(block: (QdrantEndpoint) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/healthz") { exchange -> exchange.respond(200, "healthz check passed") }
        server.start()
        try {
            block(QdrantEndpoint("127.0.0.1", server.address.port))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private inline fun withTempDirectory(prefix: String, block: (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
