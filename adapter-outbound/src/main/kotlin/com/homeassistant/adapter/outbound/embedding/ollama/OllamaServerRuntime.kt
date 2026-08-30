package com.homeassistant.adapter.outbound.embedding.ollama

import com.homeassistant.adapter.outbound.embedding.TextEmbedder
import com.homeassistant.adapter.outbound.embedding.ollama.install.PinnedOllamaDistribution
import com.homeassistant.configuration.AppConfig
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/** Owns the local embedding server process used by the application. */
interface EmbeddingServerRuntime : AutoCloseable {
    val isReady: Boolean

    fun start()
}

data class ManagedOllamaEmbedding(
    val runtime: EmbeddingServerRuntime,
    val embedder: TextEmbedder,
)

object ManagedOllamaEmbeddingFactory {
    fun create(
        runtimeRoot: Path = Path.of(AppConfig.DEFAULT_OLLAMA_RUNTIME_DIR),
        host: String = AppConfig.DEFAULT_OLLAMA_HOST,
        model: String = AppConfig.DEFAULT_EMBEDDING_MODEL_NAME,
    ): ManagedOllamaEmbedding {
        val endpoint = OllamaEndpoint.parse(host)
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        val executable = PinnedOllamaDistribution.manifest.resolveEntryPoint(normalizedRoot)
        val modelsDirectory = normalizedRoot.resolve("models")
        val embedder = OllamaEmbeddingFactory.create(endpoint.baseUrl, model)
        val runtime = OllamaServerRuntime(
            command = listOf(executable.toString(), "serve"),
            environment = environment(endpoint, modelsDirectory),
            endpoint = endpoint,
            requiredExecutable = executable,
            requiredModelsDirectory = modelsDirectory,
            embeddingProbe = { embedder.embed(READINESS_PROBE_TEXT) },
        )
        return ManagedOllamaEmbedding(runtime, embedder)
    }

    internal fun environment(endpoint: OllamaEndpoint, modelsDirectory: Path): Map<String, String> = mapOf(
        "OLLAMA_HOST" to endpoint.hostAndPort,
        "OLLAMA_MODELS" to modelsDirectory.toString(),
        "OLLAMA_DEBUG" to "false",
        "OLLAMA_KEEP_ALIVE" to "-1",
    )

    private const val READINESS_PROBE_TEXT = "home second brain embedding readiness"
}

internal fun interface OllamaProcessStarter {
    fun start(command: List<String>, environment: Map<String, String>): Process
}

internal class OllamaServerRuntime(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val endpoint: OllamaEndpoint,
    private val requiredExecutable: Path? = null,
    private val requiredModelsDirectory: Path? = null,
    private val embeddingProbe: (() -> Unit)? = null,
    private val startTimeout: Duration = Duration.ofSeconds(90),
    private val enforceFreePort: Boolean = true,
    private val processStarter: OllamaProcessStarter = OllamaProcessStarter { processCommand, processEnvironment ->
        ProcessBuilder(processCommand)
            .redirectErrorStream(true)
            .also { it.environment().putAll(processEnvironment) }
            .start()
    },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build(),
) : EmbeddingServerRuntime {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recentLogs = ArrayDeque<String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closing = false

    override val isReady: Boolean
        get() = ready && process?.isAlive == true

    @Synchronized
    override fun start() {
        check(process == null) { "Ollama server runtime has already been started" }
        requiredExecutable?.let {
            check(Files.isRegularFile(it)) {
                "Managed Ollama is not installed at $it. Run .\\gradlew.bat setupEmbedding"
            }
        }
        requiredModelsDirectory?.let {
            check(Files.isDirectory(it)) {
                "Managed Ollama model directory is missing at $it. Run .\\gradlew.bat setupEmbedding"
            }
        }
        if (enforceFreePort) checkPortIsFree()

        closing = false
        val started = processStarter.start(command, environment)
        process = started
        log.info("Managed Ollama server process started pid={}", started.pid())
        consumeLogs(started)
        started.onExit().thenRun {
            ready = false
            if (!closing) {
                log.error("Managed Ollama server exited unexpectedly with code={}", started.exitValue())
            }
        }

        try {
            awaitReadiness(started)
            try {
                embeddingProbe?.invoke()
            } catch (failure: Exception) {
                throw IllegalStateException(
                    "Embedding model is not ready. Run .\\gradlew.bat setupEmbedding",
                    failure,
                )
            }
            ready = true
            log.info("Managed Ollama server ready at {}", endpoint.baseUrl)
        } catch (failure: Exception) {
            close()
            val diagnostic = synchronized(recentLogs) { recentLogs.joinToString(System.lineSeparator()) }
            throw IllegalStateException(
                buildString {
                    append("Managed Ollama server failed to start: ")
                    append(failure.message ?: failure::class.simpleName)
                    if (diagnostic.isNotBlank()) append("\nRecent Ollama log:\n").append(diagnostic)
                },
                failure,
            )
        }
    }

    @Synchronized
    override fun close() {
        ready = false
        closing = true
        val running = process ?: return
        val descendants = running.descendants().toList()
        descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
        running.destroy()
        val rootStopped = running.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!rootStopped || descendants.any(ProcessHandle::isAlive)) {
            descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
            if (running.isAlive) running.destroyForcibly()
            if (running.isAlive) running.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        process = null
    }

    private fun checkPortIsFree() {
        check(InetAddress.getByName(endpoint.host).isLoopbackAddress) {
            "Managed Ollama host must be a loopback address: ${endpoint.host}"
        }
        Socket().use { socket ->
            val occupied = runCatching {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), PORT_CHECK_TIMEOUT_MILLIS)
            }.isSuccess
            check(!occupied) {
                "Managed Ollama port is already in use: ${endpoint.hostAndPort}"
            }
        }
    }

    private fun awaitReadiness(started: Process) {
        val deadline = System.nanoTime() + startTimeout.toNanos()
        var lastFailure: Exception? = null
        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "Ollama process exited with code=${started.exitValue()}" }
            try {
                val request = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/api/version"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                if (response.statusCode() in 200..299) return
                lastFailure = IllegalStateException("readiness returned HTTP ${response.statusCode()}")
            } catch (failure: Exception) {
                lastFailure = failure
            }
            Thread.sleep(READINESS_POLL_MILLIS)
        }
        throw IllegalStateException("Ollama readiness timed out", lastFailure)
    }

    private fun consumeLogs(started: Process) {
        Thread.ofVirtual().name("ollama-server-log").start {
            started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(recentLogs) {
                        if (recentLogs.size == MAX_RECENT_LOG_LINES) recentLogs.removeFirst()
                        recentLogs.addLast(line)
                    }
                    log.info("[ollama] {}", line)
                }
            }
        }
    }

    private companion object {
        const val PORT_CHECK_TIMEOUT_MILLIS = 250
        const val READINESS_POLL_MILLIS = 200L
        const val STOP_TIMEOUT_SECONDS = 5L
        const val MAX_RECENT_LOG_LINES = 40
    }
}

internal data class OllamaEndpoint(
    val host: String,
    val port: Int,
) {
    val hostAndPort: String = "$host:$port"
    val baseUrl: String = "http://$hostAndPort"

    companion object {
        fun parse(value: String): OllamaEndpoint {
            val separator = value.lastIndexOf(':')
            require(separator > 0 && separator < value.lastIndex) {
                "Ollama host must use host:port format"
            }
            val host = value.substring(0, separator)
            val port = value.substring(separator + 1).toIntOrNull()
            require(port != null && port in 1..65535) { "Invalid Ollama port in $value" }
            require(InetAddress.getByName(host).isLoopbackAddress) {
                "Managed Ollama host must be a loopback address: $host"
            }
            return OllamaEndpoint(host, port)
        }
    }
}
