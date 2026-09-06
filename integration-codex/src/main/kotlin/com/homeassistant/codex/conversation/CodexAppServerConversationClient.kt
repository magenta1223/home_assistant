package com.homeassistant.codex.conversation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
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
                method = "initialize",
                params = buildJsonObject {
                    put("clientInfo", buildJsonObject {
                        put("name", "home_second_brain")
                        put("title", "Home Second Brain")
                        put("version", "1")
                    })
                },
                timeout = START_TIMEOUT,
            )
            notify("initialized", buildJsonObject {})
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

    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): Result<String> = execute("start") { deadline ->
        val response = request("thread/start", threadStartParams(), deadline.remaining())
        val threadId = response.resultObject()
            ?.objectValue("thread")
            ?.string("id")
            ?.takeIf(CODEX_THREAD_ID_PATTERN::matches)
            ?: throw CodexConversationException("INVALID_THREAD_ID")
        loadedThreads += threadId
        try {
            onThreadStarted(threadId)
        } catch (_: Exception) {
            end(threadId)
            throw CodexConversationException("THREAD_PERSIST_FAILED")
        }
        log.info(
            "Latency stage=codex-thread-ready operation=start model={} elapsedMs={}",
            config.model,
            deadline.elapsedMillis(),
        )
        runTurn(threadId, prompt, deadline)
    }

    override fun resume(threadId: String, prompt: String): Result<String> = execute("resume") { deadline ->
        if (!CODEX_THREAD_ID_PATTERN.matches(threadId)) {
            throw CodexConversationException("INVALID_THREAD_ID")
        }
        if (threadId !in loadedThreads) {
            request("thread/resume", threadResumeParams(threadId), deadline.remaining())
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
                "thread/unsubscribe",
                buildJsonObject { put("threadId", threadId) },
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
        failPending("APP_SERVER_CLOSED")
        transport.close()
        loadedThreads.clear()
    }

    private fun execute(
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
            val response = request("turn/start", turnStartParams(threadId, prompt), deadline.remaining())
            state.turnId.set(
                response.resultObject()
                    ?.objectValue("turn")
                    ?.string("id")
                    ?: throw CodexConversationException("MISSING_TURN_ID"),
            )
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
                "turn/interrupt",
                buildJsonObject {
                    put("threadId", threadId)
                    put("turnId", turnId)
                },
                RELEASE_TIMEOUT,
            )
        }
    }

    private fun request(method: String, params: JsonObject, timeout: Duration): JsonObject {
        if (timeout.isZero || timeout.isNegative) throw TimeoutException(method)
        val id = requestIds.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        try {
            transport.send(buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString())
            val response = await(future, timeout)
            if (response["error"] != null) throw CodexConversationException("RPC_ERROR")
            return response
        } catch (error: Exception) {
            pending.remove(id, future)
            throw error
        }
    }

    private fun notify(method: String, params: JsonObject) {
        transport.send(buildJsonObject {
            put("method", method)
            put("params", params)
        }.toString())
    }

    private fun handleMessage(line: String) {
        val message = runCatching { CODEX_JSON.parseToJsonElement(line) as? JsonObject }
            .getOrNull() ?: return
        val method = message.string("method")
        val id = message["id"]?.jsonPrimitive?.longOrNull
        if (method == null && id != null) {
            pending.remove(id)?.complete(message)
            return
        }
        if (method != null && id != null) {
            respondUnsupported(message["id"] ?: JsonPrimitive(id))
            return
        }
        val params = message.objectValue("params") ?: return
        when (method) {
            "item/completed" -> recordAgentMessage(params)
            "turn/completed" -> completeTurn(params)
            "thread/closed" -> params.string("threadId")?.let(loadedThreads::remove)
        }
    }

    private fun recordAgentMessage(params: JsonObject) {
        val state = params.string("threadId")?.let(activeTurns::get) ?: return
        val item = params.objectValue("item") ?: return
        if (item.string("type") == "agentMessage") {
            item.string("text")?.let(state.answer::set)
        }
    }

    private fun completeTurn(params: JsonObject) {
        val state = params.string("threadId")?.let(activeTurns::get) ?: return
        val turn = params.objectValue("turn") ?: return
        val items = turn["items"] as? kotlinx.serialization.json.JsonArray
        items.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "agentMessage" }
            .mapNotNull { it.string("text") }
            .lastOrNull()
            ?.let(state.answer::set)
        val status = turn.string("status") ?: "failed"
        state.completed.complete(TurnCompletion(status, state.answer.get()))
    }

    private fun respondUnsupported(id: JsonElement) {
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
        activeTurns.values.forEach { it.completed.completeExceptionally(failure) }
        activeTurns.clear()
    }

    private fun threadStartParams(): JsonObject = buildJsonObject {
        put("model", config.model)
        put("cwd", config.workDir.toString())
        put("approvalPolicy", "never")
        put("sandbox", "read-only")
        put("serviceName", "home_second_brain")
        put("config", turnConfig())
    }

    private fun threadResumeParams(threadId: String): JsonObject = buildJsonObject {
        put("threadId", threadId)
        put("model", config.model)
        put("cwd", config.workDir.toString())
        put("approvalPolicy", "never")
        put("sandbox", "read-only")
        put("config", turnConfig())
    }

    private fun turnStartParams(threadId: String, prompt: String): JsonObject = buildJsonObject {
        put("threadId", threadId)
        put("input", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", prompt)
            })
        })
        put("cwd", config.workDir.toString())
        put("approvalPolicy", "never")
        put("sandboxPolicy", buildJsonObject { put("type", "readOnly") })
        put("model", config.model)
        put("effort", config.reasoningEffort)
        put("outputSchema", ANSWER_SCHEMA)
    }

    private fun turnConfig(): JsonObject = buildJsonObject {
        put("web_search", "disabled")
        put("model_reasoning_effort", config.reasoningEffort)
    }

    private fun JsonObject.resultObject(): JsonObject? = objectValue("result")
    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

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
        val ANSWER_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("answer", buildJsonObject { put("type", "string") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("answer")) })
            put("additionalProperties", false)
        }
    }
}

internal fun parseStructuredAnswer(raw: String?): String? {
    val result = raw?.let {
        runCatching { STRUCTURED_ANSWER_JSON.parseToJsonElement(it) as? JsonObject }.getOrNull()
    } ?: return null
    if (result.keys != setOf("answer")) return null
    val answer = result["answer"] as? JsonPrimitive ?: return null
    if (!answer.isString) return null
    return answer.content.trim().takeIf(String::isNotEmpty)
}

private val STRUCTURED_ANSWER_JSON = Json.Default

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
