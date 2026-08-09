package com.homeassistant.adapter.outbound.vector.qdrant

import com.homeassistant.adapter.outbound.vector.VectorStore
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

/** Owns the local vector database process used by the application. */
interface VectorServerRuntime : AutoCloseable {
    val isReady: Boolean

    fun start()
}

data class ManagedQdrantVectorStore(
    val runtime: VectorServerRuntime,
    val store: VectorStore,
)

object ManagedQdrantVectorStoreFactory {
    fun create(
        runtimeRoot: Path = Path.of(AppConfig.DEFAULT_QDRANT_RUNTIME_DIR),
        baseUrl: String = AppConfig.DEFAULT_QDRANT_URL,
        collection: String = AppConfig.DEFAULT_QDRANT_COLLECTION,
    ): ManagedQdrantVectorStore {
        val endpoint = QdrantEndpoint.parse(baseUrl)
        val normalizedRoot = runtimeRoot.toAbsolutePath().normalize()
        val executable = QdrantDistributionInstaller().executable(normalizedRoot)
        val storageDirectory = normalizedRoot.resolve("storage")
        val snapshotsDirectory = normalizedRoot.resolve("snapshots")
        return ManagedQdrantVectorStore(
            runtime = QdrantServerRuntime(
                command = listOf(executable.toString()),
                environment = mapOf(
                    "QDRANT__LOG_LEVEL" to "WARN",
                    "QDRANT__TELEMETRY_DISABLED" to "true",
                    "QDRANT__SERVICE__HOST" to endpoint.host,
                    "QDRANT__SERVICE__HTTP_PORT" to endpoint.port.toString(),
                    "QDRANT__SERVICE__GRPC_PORT" to (endpoint.port + 1).toString(),
                    "QDRANT__STORAGE__STORAGE_PATH" to storageDirectory.toString(),
                    "QDRANT__STORAGE__SNAPSHOTS_PATH" to snapshotsDirectory.toString(),
                ),
                workingDirectory = normalizedRoot,
                endpoint = endpoint,
                requiredExecutable = executable,
                storageDirectory = storageDirectory,
                snapshotsDirectory = snapshotsDirectory,
            ),
            store = QdrantVectorStoreFactory.create(baseUrl, collection),
        )
    }
}

/** Installs the pinned local Qdrant executable without starting the long-running server. */
object QdrantRuntimeSetup {
    fun prepare(runtimeRoot: Path = Path.of(AppConfig.DEFAULT_QDRANT_RUNTIME_DIR)): Path =
        QdrantDistributionInstaller().install(runtimeRoot.toAbsolutePath().normalize())
}

internal fun interface QdrantProcessStarter {
    fun start(command: List<String>, environment: Map<String, String>, workingDirectory: Path): Process
}

internal class QdrantServerRuntime(
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val workingDirectory: Path,
    private val endpoint: QdrantEndpoint,
    private val requiredExecutable: Path? = null,
    private val storageDirectory: Path? = null,
    private val snapshotsDirectory: Path? = null,
    private val startTimeout: Duration = Duration.ofSeconds(30),
    private val enforceFreePort: Boolean = true,
    private val processStarter: QdrantProcessStarter = QdrantProcessStarter { processCommand, processEnvironment, directory ->
        ProcessBuilder(processCommand)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .also { it.environment().putAll(processEnvironment) }
            .start()
    },
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(1))
        .build(),
) : VectorServerRuntime {
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
        check(process == null) { "Qdrant server runtime has already been started" }
        requiredExecutable?.let {
            check(Files.isRegularFile(it)) {
                "Managed Qdrant is not installed at $it. Run .\\gradlew.bat setupQdrant"
            }
        }
        if (enforceFreePort) checkPortIsFree()
        Files.createDirectories(workingDirectory)
        storageDirectory?.let(Files::createDirectories)
        snapshotsDirectory?.let(Files::createDirectories)

        closing = false
        val started = processStarter.start(command, environment, workingDirectory)
        process = started
        log.info("Managed Qdrant server process started pid={}", started.pid())
        consumeLogs(started)
        started.onExit().thenRun {
            ready = false
            if (!closing) log.error("Managed Qdrant server exited unexpectedly with code={}", started.exitValue())
        }

        try {
            awaitReadiness(started)
            ready = true
            log.info("Managed Qdrant server ready at {}", endpoint.baseUrl)
        } catch (failure: Exception) {
            close()
            val diagnostic = synchronized(recentLogs) { recentLogs.joinToString(System.lineSeparator()) }
            throw IllegalStateException(
                buildString {
                    append("Managed Qdrant server failed to start: ")
                    append(failure.message ?: failure::class.simpleName)
                    if (diagnostic.isNotBlank()) append("\nRecent Qdrant log:\n").append(diagnostic)
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
        Socket().use { socket ->
            val occupied = runCatching {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), PORT_CHECK_TIMEOUT_MILLIS)
            }.isSuccess
            check(!occupied) { "Managed Qdrant port is already in use: ${endpoint.hostAndPort}" }
        }
    }

    private fun awaitReadiness(started: Process) {
        val deadline = System.nanoTime() + startTimeout.toNanos()
        var lastFailure: Exception? = null
        while (System.nanoTime() < deadline) {
            check(started.isAlive) { "Qdrant process exited with code=${started.exitValue()}" }
            try {
                val response = client.send(
                    HttpRequest.newBuilder(URI.create("${endpoint.baseUrl}/healthz"))
                        .timeout(Duration.ofSeconds(2))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding(),
                )
                if (response.statusCode() in 200..299) return
                lastFailure = IllegalStateException("readiness returned HTTP ${response.statusCode()}")
            } catch (failure: Exception) {
                lastFailure = failure
            }
            Thread.sleep(READINESS_POLL_MILLIS)
        }
        throw IllegalStateException("Qdrant readiness timed out", lastFailure)
    }

    private fun consumeLogs(started: Process) {
        Thread.ofVirtual().name("qdrant-server-log").start {
            started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(recentLogs) {
                        if (recentLogs.size == MAX_RECENT_LOG_LINES) recentLogs.removeFirst()
                        recentLogs.addLast(line)
                    }
                    log.info("[qdrant] {}", line)
                }
            }
        }
    }

    private companion object {
        const val PORT_CHECK_TIMEOUT_MILLIS = 250
        const val READINESS_POLL_MILLIS = 200L
        const val STOP_TIMEOUT_SECONDS = 10L
        const val MAX_RECENT_LOG_LINES = 40
    }
}

internal data class QdrantEndpoint(
    val host: String,
    val port: Int,
) {
    val hostAndPort: String = "$host:$port"
    val baseUrl: String = "http://$hostAndPort"

    companion object {
        fun parse(value: String): QdrantEndpoint {
            val uri = URI.create(value)
            require(uri.scheme == "http" && uri.userInfo == null && uri.path.orEmpty() in setOf("", "/")) {
                "Managed Qdrant URL must use http://host:port without credentials or a path"
            }
            require(!uri.host.isNullOrBlank()) { "Managed Qdrant URL must include a host" }
            require(uri.port in 1 until 65535) { "Managed Qdrant URL must include a valid HTTP port" }
            val address = runCatching { InetAddress.getByName(uri.host) }
                .getOrElse { throw IllegalArgumentException("Managed Qdrant host cannot be resolved: ${uri.host}", it) }
            require(address.isLoopbackAddress) {
                "Managed Qdrant host must be a loopback address: ${uri.host}"
            }
            return QdrantEndpoint(uri.host, uri.port)
        }
    }
}
