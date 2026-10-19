package com.homeassistant.llm.codex.conversation

import com.homeassistant.llm.codex.transport.CodexAppServerTransport
import com.homeassistant.llm.codex.transport.CodexAppServerTransportFactory
import com.homeassistant.llm.codex.transport.protocol.AppServerProtocol
import com.homeassistant.llm.codex.transport.protocol.AppServerThreadItem
import com.homeassistant.llm.codex.transport.protocol.ClientInfo
import com.homeassistant.llm.codex.transport.protocol.CODEX_JSON
import com.homeassistant.llm.codex.transport.protocol.TextUserInput
import com.homeassistant.llm.codex.transport.protocol.ThreadConfig
import kotlinx.schema.generator.json.JsonSchemaConfig
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
import java.util.concurrent.atomic.AtomicReference

internal class DefaultCodexAppServer(
    private val config: CodexConversationConfig,
    private val transport: CodexAppServerTransport = CodexAppServerTransportFactory.create(
        command = appServerCommand(config),
        workDir = config.workDir,
    ),
    private val availabilityProbe: () -> Boolean = { probeCodexVersion(config) },
) : CodexAppServer, CodexAppServerTransport.Listener {
    private val log = LoggerFactory.getLogger(javaClass)
    private val activeTurns = ConcurrentHashMap<String, TurnState>()
    private val loadedThreads = ConcurrentHashMap.newKeySet<String>()
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val restartScheduled = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val restarter = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "codex-app-server-restart").apply { isDaemon = true }
    }

    internal fun prepare(): Boolean = availabilityProbe() && startServer()

    private fun startServer(): Boolean = synchronized(lifecycleLock) {
        if (closed.get()) return false
        val startedAt = System.nanoTime()
        if (!transport.start(this)) {
            log.warn("Latency stage=codex-app-server-start result=failure elapsedMs={}", elapsedMillis(startedAt))
            return false
        }
        if (ready.get()) return true
        return try {
            await(
                transport.initialize(
                    AppServerProtocol.Initialize(
                        ClientInfo(
                            name = "home_second_brain",
                            title = "Home Second Brain",
                            version = "1",
                        ),
                    ),
                ),
                START_TIMEOUT,
            )
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
            failActiveTurns("APP_SERVER_START_FAILED")
            log.warn(
                "Latency stage=codex-app-server-start result=failure category={} elapsedMs={}",
                error.javaClass.simpleName,
                elapsedMillis(startedAt),
            )
            false
        }
    }

    override fun createThread(): Result<CodexThreadId> = executeOperation("create") { deadline ->
        val response = await(transport.startThread(threadStartMessage()), deadline.remaining())
        val threadId = response.thread.id.takeIf(CODEX_THREAD_ID_PATTERN::matches)
            ?: throw CodexConversationException("INVALID_THREAD_ID")
        loadedThreads += threadId
        log.info(
            "Latency stage=codex-thread-ready operation=create model={} elapsedMs={}",
            config.model,
            deadline.elapsedMillis(),
        )
        CodexThreadId(threadId)
    }

    override fun executeTurn(threadId: CodexThreadId, prompt: String): Result<String> = executeOperation("execute") { deadline ->
        val threadIdValue = threadId.value
        if (!CODEX_THREAD_ID_PATTERN.matches(threadIdValue)) {
            throw CodexConversationException("INVALID_THREAD_ID")
        }
        if (threadIdValue !in loadedThreads) {
            await(transport.resumeThread(threadResumeMessage(threadIdValue)), deadline.remaining())
            loadedThreads += threadIdValue
        }
        runTurn(threadIdValue, prompt, deadline)
    }

    override fun releaseThread(threadId: CodexThreadId) {
        val threadIdValue = threadId.value
        if (!CODEX_THREAD_ID_PATTERN.matches(threadIdValue)) return
        loadedThreads.remove(threadIdValue)
        if (!ready.get()) return
        runCatching {
            await(
                transport.unsubscribeThread(
                    AppServerProtocol.ThreadUnsubscribe(threadIdValue),
                ),
                RELEASE_TIMEOUT,
            )
        }.onFailure {
            log.warn("Failed to unsubscribe Codex thread category={}", it.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.set(false)
        restarter.shutdownNow()
        failActiveTurns("APP_SERVER_CLOSED")
        activeTurns.clear()
        transport.close()
        loadedThreads.clear()
    }

    private fun <T> executeOperation(
        operation: String,
        block: (Deadline) -> T,
    ): Result<T> {
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
            val response = await(transport.startTurn(turnStartMessage(threadId, prompt)), deadline.remaining())
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
            await(
                transport.interruptTurn(AppServerProtocol.TurnInterrupt(threadId, turnId)),
                RELEASE_TIMEOUT,
            )
        }
    }

    override fun onItemCompleted(event: AppServerProtocol.ItemCompleted) {
        recordAgentMessage(event)
    }

    override fun onTurnCompleted(event: AppServerProtocol.TurnCompleted) {
        completeTurn(event)
    }

    override fun onThreadClosed(event: AppServerProtocol.ThreadClosed) {
        loadedThreads.remove(event.threadId)
    }

    override fun onProtocolError() {
        failActiveTurns("INVALID_APP_SERVER_MESSAGE")
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

    override fun onAppServerExited() {
        ready.set(false)
        loadedThreads.clear()
        failActiveTurns("APP_SERVER_EXITED")
        activeTurns.clear()
        if (!closed.get()) scheduleRestart(RESTART_DELAY_SECONDS)
    }

    private fun scheduleRestart(delaySeconds: Long) {
        if (!restartScheduled.compareAndSet(false, true) || closed.get()) return
        restarter.schedule({
            restartScheduled.set(false)
            if (!closed.get() && !startServer()) scheduleRestart(RETRY_DELAY_SECONDS)
        }, delaySeconds, TimeUnit.SECONDS)
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

private val CODEX_THREAD_ID_PATTERN =
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

private fun <T> await(future: CompletableFuture<T>, timeout: Duration): T = try {
    future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
} catch (error: TimeoutException) {
    future.cancel(false)
    throw error
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

internal fun probeCodexVersion(config: CodexConversationConfig): Boolean {
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
