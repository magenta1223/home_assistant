package com.homeassistant.adapter.outbound.embedding.ollama

import com.homeassistant.adapter.outbound.embedding.ollama.install.PinnedOllamaDistribution
import com.homeassistant.application.port.outbound.embedding.EmbeddingGenerator
import com.homeassistant.application.port.outbound.embedding.EmbeddingSpace
import com.homeassistant.configuration.AppConfig
import com.homeassistant.domain.group.ComponentVector
import com.homeassistant.infrastructure.serialization.JsonSerializer
import kotlinx.serialization.Serializable
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

/** Builds the runtime of the managed Ollama installation. */
object ManagedOllamaEmbeddingFactory {
    /** The runtime that serves [model] from [runtimeRoot] at [host]. */
    fun create(
        runtimeRoot: Path = Path.of(AppConfig.DEFAULT_OLLAMA_RUNTIME_DIR),
        host: String = AppConfig.DEFAULT_OLLAMA_HOST,
        model: String = AppConfig.DEFAULT_EMBEDDING_MODEL_NAME,
    ): OllamaServerRuntime {
        val endpoint = OllamaEndpoint.parse(host)
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        val executable = PinnedOllamaDistribution.manifest.resolveEntryPoint(normalizedRoot)
        val modelsDirectory = normalizedRoot.resolve("models")
        return OllamaServerRuntime(
            command = listOf(executable.toString(), "serve"),
            environment = environment(endpoint, modelsDirectory),
            endpoint = endpoint,
            model = model,
            requiredExecutable = executable,
            requiredModelsDirectory = modelsDirectory,
        )
    }

    internal fun environment(endpoint: OllamaEndpoint, modelsDirectory: Path): Map<String, String> = mapOf(
        "OLLAMA_HOST" to endpoint.hostAndPort,
        "OLLAMA_MODELS" to modelsDirectory.toString(),
        "OLLAMA_DEBUG" to "false",
        "OLLAMA_KEEP_ALIVE" to "-1",
    )
}

internal fun interface OllamaProcessStarter {
    fun start(command: List<String>, environment: Map<String, String>): Process
}

/**
 * A managed Ollama server serving one e5 model: it starts and stops the server process, reports readiness and
 * embeds text. e5 expects `passage: ` before stored text and `query: ` before questions.
 */
class OllamaServerRuntime internal constructor(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val endpoint: OllamaEndpoint,
    private val model: String,
    private val vectorSize: Int = AppConfig.DEFAULT_EMBEDDING_VECTOR_SIZE,
    private val requiredExecutable: Path? = null,
    private val requiredModelsDirectory: Path? = null,
    private val startTimeout: Duration = Duration.ofSeconds(90),
    private val enforceFreePort: Boolean = true,
    private val processStarter: OllamaProcessStarter = OllamaProcessStarter { processCommand, processEnvironment ->
        ProcessBuilder(processCommand)
            .redirectErrorStream(true)
            .also { it.environment().putAll(processEnvironment) }
            .start()
    },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) : EmbeddingGenerator, EmbeddingServerRuntime {
    private val log = LoggerFactory.getLogger(javaClass)
    private val recentLogs = ArrayDeque<String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var ready = false

    @Volatile
    private var closing = false

    override val space = EmbeddingSpace(model, "episode-content:passage:v1")

    override val isReady: Boolean
        get() = ready && process?.isAlive == true

    override fun isAvailable(): Boolean = isReady

    /** Starts the server and loads the model with one probe embedding; ready only when the model answers. */
    @Synchronized
    override fun start() {
        startServer()
        try {
            embedDocument(READINESS_PROBE_TEXT)
        } catch (failure: Exception) {
            close()
            throw IllegalStateException("Embedding model is not ready. Run .\\gradlew.bat setupEmbedding", failure)
        }
        ready = true
    }

    /** Starts the server process and waits until its API answers, without requiring the model to be installed. */
    @Synchronized
    internal fun startServer() {
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

    override fun embedDocument(text: String): ComponentVector = embed("passage: $text")

    override fun embedQuery(text: String): ComponentVector = embed("query: $text")

    private fun embed(input: String): ComponentVector {
        val startedAt = System.nanoTime()
        return try {
            val request = HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/api/embed"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JsonSerializer.json.encodeToString(OllamaEmbedRequest(model = model, input = input.trim())),
                    ),
                )
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) {
                "Ollama embedding request failed status=${response.statusCode()} body=${response.body()}"
            }

            val vector = JsonSerializer.json
                .decodeFromString<OllamaEmbedResponse>(response.body())
                .embeddings
                .firstOrNull()
                ?: error("Ollama embedding response did not include embeddings")
            check(vector.size == vectorSize) {
                "Embedding vector size mismatch: expected=$vectorSize actual=${vector.size}"
            }
            ComponentVector.normalized(vector.map { it.toDouble() }).also {
                log.info(
                    "Latency stage=embedding-request result=success model={} elapsedMs={}",
                    model,
                    elapsedMillis(startedAt),
                )
            }
        } catch (error: Exception) {
            log.warn(
                "Latency stage=embedding-request result=failure model={} category={} elapsedMs={}",
                model,
                error.javaClass.simpleName,
                elapsedMillis(startedAt),
            )
            throw error
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

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
        const val READINESS_PROBE_TEXT = "home second brain embedding readiness"
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

@Serializable
private data class OllamaEmbedRequest(
    val model: String,
    val input: String,
)

@Serializable
private data class OllamaEmbedResponse(
    val embeddings: List<List<Float>> = emptyList(),
)
