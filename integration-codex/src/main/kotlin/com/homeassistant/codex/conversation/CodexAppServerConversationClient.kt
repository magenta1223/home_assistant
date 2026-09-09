package com.homeassistant.codex.conversation

import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class CodexAppServerConversationClient(
    private val config: CodexConversationConfig,
    private val transport: AppServerTransport = ProcessCodexAppServerTransport(
        command = appServerCommand(config),
        workDir = config.workDir,
    ),
    private val availabilityProbe: () -> Boolean = { probeCodexVersion(config) },
) : ConversationClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<IncomingAppServerMessage>>()
    private val activeTurns = ConcurrentHashMap<String, TurnState>()
    private val loadedThreads = ConcurrentHashMap.newKeySet<String>()
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val restarter = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "codex-app-server-restart").apply { isDaemon = true }
    }

    override fun isAvailable(): Boolean = availabilityProbe()

    override fun startServer(): Boolean = synchronized(lifecycleLock) {
        if (closed.get()) return false
        if (ready.get() && transport.isAlive) return true
        val startedAt = System.nanoTime()
        if (!transport.start(::handleMessage, ::handleTransportClosed)) {
            log.warn("Latency stage=codex-app-server-start result=failure elapsedMs={}", elapsedMillis(startedAt))
            return false
        }
        return try {
            request(
                message = AppServerProtocol.Initialize(
                    ClientInfo(
                        name = "home_second_brain",
                        title = "Home Second Brain",
                        version = "1",
                    ),
                ),
                timeout = START_TIMEOUT,
            ).decode<JsonObject>()
            notify(AppServerProtocol.Initialized)
            loadedThreads.clear()
            ready.set(true)
            log.info(
                "Latency stage=codex-app-server-start result=success model={} reasoningEffort={} elapsedMs={}",
                config.model,
                config.reasoningEffort,
                elapsedMillis(startedAt),
            )
            true
        } catch (error: Exception) {
            ready.set(false)
            transport.stop()
            failPending("APP_SERVER_START_FAILED")
            log.warn(
                "Latency stage=codex-app-server-start result=failure category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(startedAt),
            )
            false
        }
    }

    override fun create(): Result<String> = executeOperation("create") { deadline ->
        val response = request(threadStartMessage(), deadline.remaining())
            .decode<ThreadResult>()
        val threadId = response.thread.id.takeIf(CODEX_THREAD_ID_PATTERN::matches)
            ?: throw CodexConversationException("INVALID_THREAD_ID")
        loadedThreads += threadId
        log.info(
            "Latency stage=codex-thread-ready operation=create model={} elapsedMs={}",
            config.model,
            deadline.elapsedMillis(),
        )
        threadId
    }

    override fun execute(threadId: String, prompt: String): Result<String> = executeOperation("execute") { deadline ->
        if (!CODEX_THREAD_ID_PATTERN.matches(threadId)) {
            throw CodexConversationException("INVALID_THREAD_ID")
        }
        if (threadId !in loadedThreads) {
            request(threadResumeMessage(threadId), deadline.remaining())
                .decode<ThreadResult>()
            loadedThreads += threadId
        }
        runTurn(threadId, prompt, deadline)
    }

    override fun end(threadId: String) {
        if (!CODEX_THREAD_ID_PATTERN.matches(threadId)) return
        loadedThreads.remove(threadId)
        if (!ready.get() || !transport.isAlive) return
        runCatching {
            request(
                AppServerProtocol.ThreadUnsubscribe(threadId),
                RELEASE_TIMEOUT,
            ).decode<JsonObject>()
        }.onFailure {
            log.warn("Failed to unsubscribe Codex thread category={}", it.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.set(false)
        restarter.shutdownNow()
        failPending("APP_SERVER_CLOSED")
        transport.close()
        loadedThreads.clear()
    }

    private fun executeOperation(
        operation: String,
        block: (Deadline) -> String,
    ): Result<String> {
        if (!startServer()) return Result.failure(CodexConversationException("APP_SERVER_UNAVAILABLE"))
        val startedAt = System.nanoTime()
        val result = try {
            if (closed.get()) throw CodexConversationException("APP_SERVER_CLOSED")
            block(Deadline(config.timeout))
        } catch (error: CodexConversationException) {
            return Result.failure(error)
        } catch (_: TimeoutException) {
            return Result.failure(CodexConversationException("TIMEOUT"))
        } catch (_: Exception) {
            return Result.failure(CodexConversationException("APP_SERVER_FAILURE"))
        }
        log.info(
            "Latency stage=codex-turn operation={} result=completed model={} elapsedMs={}",
            operation,
            config.model,
            elapsedMillis(startedAt),
        )
        return Result.success(result)
    }

    private fun runTurn(threadId: String, prompt: String, deadline: Deadline): String {
        if (prompt.isBlank()) throw CodexConversationException("EMPTY_PROMPT")
        val state = TurnState()
        if (activeTurns.putIfAbsent(threadId, state) != null) {
            throw CodexConversationException("THREAD_BUSY")
        }
        try {
            val response = request(turnStartMessage(threadId, prompt), deadline.remaining())
                .decode<TurnStartResult>()
            state.turnId.set(response.turn.id)
            val completion = await(state.completed, deadline.remaining())
            if (completion.status != "completed") {
                throw CodexConversationException("TURN_${completion.status.uppercase()}")
            }
            return parseStructuredAnswer(completion.answer)
                ?: throw CodexConversationException("INVALID_STRUCTURED_ANSWER")
        } catch (error: TimeoutException) {
            interrupt(threadId, state.turnId.get())
            throw error
        } finally {
            activeTurns.remove(threadId, state)
        }
    }

    private fun interrupt(threadId: String, turnId: String?) {
        if (turnId == null || !ready.get()) return
        runCatching {
            request(
                AppServerProtocol.TurnInterrupt(threadId, turnId),
                RELEASE_TIMEOUT,
            ).decode<JsonObject>()
        }
    }

    private inline fun <reified P : AppServerProtocol> request(
        message: P,
        timeout: Duration,
    ): JsonElement {
        if (timeout.isZero || timeout.isNegative) throw TimeoutException(message.method)
        val id = requestIds.getAndIncrement()
        val future = CompletableFuture<IncomingAppServerMessage>()
        pending[id] = future
        try {
            transport.send(buildJsonObject {
                put("id", id)
                put("method", message.method)
                put("params", CODEX_JSON.encodeToJsonElement(message))
            }.toString())
            val response = await(future, timeout)
            if (response.error != null) throw CodexConversationException("RPC_ERROR")
            return response.result ?: throw CodexConversationException("MISSING_RPC_RESULT")
        } catch (error: Exception) {
            pending.remove(id, future)
            throw error
        }
    }

    private inline fun <reified P : AppServerProtocol> notify(message: P) {
        transport.send(buildJsonObject {
            put("method", message.method)
            put("params", CODEX_JSON.encodeToJsonElement(message))
        }.toString())
    }

    private fun handleMessage(line: String) {
        val message = try {
            CODEX_JSON.decodeFromString(IncomingAppServerMessage.serializer(), line)
        } catch (error: Exception) {
            log.warn("Ignored malformed Codex app-server message category={}", error.javaClass.simpleName)
            failPending("INVALID_APP_SERVER_MESSAGE")
            return
        }
        val method = message.method
        val id = message.id
        if (method == null && id != null) {
            id.longOrNull?.let { pending.remove(it)?.complete(message) }
                ?: log.warn("Ignored Codex response with unexpected request id")
            return
        }
        if (method != null && id != null) {
            respondUnsupported(id)
            return
        }
        when (method) {
            AppServerProtocol.ItemCompleted.METHOD -> decodeNotification(
                AppServerProtocol.ItemCompleted.METHOD,
                message.params,
                ::recordAgentMessage,
            )
            AppServerProtocol.TurnCompleted.METHOD -> decodeNotification(
                AppServerProtocol.TurnCompleted.METHOD,
                message.params,
                ::completeTurn,
            )
            AppServerProtocol.ThreadClosed.METHOD -> decodeNotification<AppServerProtocol.ThreadClosed>(
                AppServerProtocol.ThreadClosed.METHOD,
                message.params,
            ) { loadedThreads.remove(it.threadId) }
            null -> log.warn("Ignored malformed Codex app-server message without method or response id")
        }
    }

    private inline fun <reified P> decodeNotification(
        method: String,
        params: JsonElement?,
        handle: (P) -> Unit,
    ) {
        val decoded = try {
            requireNotNull(params) { "Missing notification params" }
            CODEX_JSON.decodeFromJsonElement<P>(params)
        } catch (error: Exception) {
            log.warn(
                "Rejected malformed Codex app-server notification method={} category={}",
                method,
                error.javaClass.simpleName,
            )
            failActiveTurns("INVALID_APP_SERVER_MESSAGE")
            return
        }
        handle(decoded)
    }

    private fun recordAgentMessage(params: AppServerProtocol.ItemCompleted) {
        val state = activeTurns[params.threadId] ?: return
        if (params.item.type == "agentMessage") {
            params.item.text?.let(state.answer::set)
        }
    }

    private fun completeTurn(params: AppServerProtocol.TurnCompleted) {
        val state = activeTurns[params.threadId] ?: return
        params.turn.items
            .filter { it.type == "agentMessage" }
            .mapNotNull(AppServerThreadItem::text)
            .lastOrNull()
            ?.let(state.answer::set)
        state.completed.complete(TurnCompletion(params.turn.status, state.answer.get()))
    }

    private fun respondUnsupported(id: JsonPrimitive) {
        runCatching {
            transport.send(buildJsonObject {
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", "Unsupported server request")
                })
            }.toString())
        }
    }

    private fun handleTransportClosed() {
        ready.set(false)
        loadedThreads.clear()
        failPending("APP_SERVER_EXITED")
        if (!closed.get()) scheduleRestart(RESTART_DELAY_SECONDS)
    }

    private fun scheduleRestart(delaySeconds: Long) {
        if (!restartScheduled.compareAndSet(false, true) || closed.get()) return
        restarter.schedule({
            restartScheduled.set(false)
            if (!closed.get() && !startServer()) scheduleRestart(RETRY_DELAY_SECONDS)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun failPending(category: String) {
        val failure = CodexConversationException(category)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        failActiveTurns(category)
        activeTurns.clear()
    }

    private fun failActiveTurns(category: String) {
        val failure = CodexConversationException(category)
        activeTurns.values.forEach { it.completed.completeExceptionally(failure) }
    }

    private fun threadStartMessage(): AppServerProtocol.ThreadStart = AppServerProtocol.ThreadStart(
        model = config.model,
        cwd = config.workDir.toString(),
        config = turnConfig(),
    )

    private fun threadResumeMessage(threadId: String): AppServerProtocol.ThreadResume = AppServerProtocol.ThreadResume(
        threadId = threadId,
        model = config.model,
        cwd = config.workDir.toString(),
        config = turnConfig(),
    )

    private fun turnStartMessage(threadId: String, prompt: String): AppServerProtocol.TurnStart = AppServerProtocol.TurnStart(
        threadId = threadId,
        input = listOf(TextUserInput(text = prompt)),
        cwd = config.workDir.toString(),
        model = config.model,
        effort = config.reasoningEffort,
        outputSchema = StructuredAnswerContract.schema,
    )

    private fun turnConfig(): ThreadConfig = ThreadConfig(modelReasoningEffort = config.reasoningEffort)

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    private data class TurnState(
        val turnId: AtomicReference<String> = AtomicReference(),
        val answer: AtomicReference<String> = AtomicReference(),
        val completed: CompletableFuture<TurnCompletion> = CompletableFuture(),
    )

    private data class TurnCompletion(val status: String, val answer: String?)

    private class Deadline(timeout: Duration) {
        private val startedAt = System.nanoTime()
        private val timeoutNanos = timeout.toNanos()

        fun remaining(): Duration {
            val remaining = timeoutNanos - (System.nanoTime() - startedAt)
            if (remaining <= 0) throw TimeoutException("deadline")
            return Duration.ofNanos(remaining)
        }

        fun elapsedMillis(): Long = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
    }

    private companion object {
        val START_TIMEOUT: Duration = Duration.ofSeconds(30)
        val RELEASE_TIMEOUT: Duration = Duration.ofSeconds(5)
        const val RESTART_DELAY_SECONDS = 1L
        const val RETRY_DELAY_SECONDS = 5L
    }
}

internal fun parseStructuredAnswer(raw: String?): String? {
    val result = raw?.let {
        runCatching { STRUCTURED_ANSWER_JSON.decodeFromString(StructuredAnswer.serializer(), it) }.getOrNull()
    } ?: return null
    return result.answer.trim().takeIf(String::isNotEmpty)
}

private val STRUCTURED_ANSWER_JSON = Json.Default

@OptIn(ExperimentalSerializationApi::class)
private object StructuredAnswerContract {
    val schema: JsonElement = SerializationClassJsonSchemaGenerator(
        json = CODEX_JSON,
        jsonSchemaConfig = JsonSchemaConfig.Strict,
    )
        .generateSchemaString(StructuredAnswer.serializer().descriptor)
        .let(CODEX_JSON::parseToJsonElement)
}

private inline fun <reified T> JsonElement.decode(): T =
    runCatching { CODEX_JSON.decodeFromJsonElement<T>(this) }
        .getOrElse { throw CodexConversationException("INVALID_RPC_RESULT") }

private val CODEX_THREAD_ID_PATTERN =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private fun <T> await(future: CompletableFuture<T>, timeout: Duration): T = try {
    future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
} catch (error: ExecutionException) {
    throw error.cause ?: error
}

private fun appServerCommand(config: CodexConversationConfig): List<String> = listOf(
    config.executable,
    "app-server",
    "--stdio",
    "-c",
    "approval_policy=\"never\"",
    "-c",
    "sandbox_mode=\"read-only\"",
    "-c",
    "web_search=\"disabled\"",
    "-c",
    "model=\"${config.model}\"",
    "-c",
    "model_reasoning_effort=\"${config.reasoningEffort}\"",
)

private fun probeCodexVersion(config: CodexConversationConfig): Boolean {
    val process = runCatching {
        ProcessBuilder(config.executable, "--version")
            .directory(config.workDir.toFile())
            .redirectErrorStream(true)
            .start()
    }.getOrNull() ?: return false
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return false
    }
    val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
    return process.exitValue() == 0 && Regex("""codex-cli\s+([0-9A-Za-z.+-]+)""").containsMatchIn(output)
}
