package com.homeassistant.app.slack

import com.homeassistant.core.utils.JsonSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface CodexTurnResult {
    data class Success(val answer: String) : CodexTurnResult
    data class Failure(val category: String) : CodexTurnResult
}

interface CodexConversationClient {
    fun validateVersion(): Boolean
    fun start(prompt: String, onThreadStarted: (String) -> Unit): CodexTurnResult
    fun resume(threadId: String, prompt: String): CodexTurnResult
}

class ProcessCodexConversationClient(
    private val config: CodexConversationConfig,
) : CodexConversationClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun validateVersion(): Boolean {
        val process = runCatching {
            ProcessBuilder(config.executable.toString(), "--version")
                .directory(config.workDir.toFile())
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val version = VERSION_PATTERN.find(output)?.groupValues?.get(1)
        return process.exitValue() == 0 && version == config.expectedVersion
    }

    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): CodexTurnResult =
        execute(
            args = listOf(
                "exec",
                "--json",
                "--sandbox",
                "read-only",
                "--skip-git-repo-check",
                "--ignore-user-config",
                "--ignore-rules",
                "-c",
                "approval_policy=\"never\"",
                "-c",
                "web_search=\"disabled\"",
                "-C",
                config.workDir.toString(),
                "-",
            ),
            prompt = prompt,
            onThreadStarted = onThreadStarted,
        )

    override fun resume(threadId: String, prompt: String): CodexTurnResult {
        if (!THREAD_ID_PATTERN.matches(threadId)) return CodexTurnResult.Failure("INVALID_THREAD_ID")
        return execute(
            args = listOf(
                "exec",
                "resume",
                "--json",
                "--skip-git-repo-check",
                "--ignore-user-config",
                "--ignore-rules",
                "-c",
                "approval_policy=\"never\"",
                "-c",
                "sandbox_mode=\"read-only\"",
                "-c",
                "web_search=\"disabled\"",
                threadId,
                "-",
            ),
            prompt = prompt,
            onThreadStarted = {},
        )
    }

    private fun execute(
        args: List<String>,
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): CodexTurnResult {
        if (prompt.isBlank()) return CodexTurnResult.Failure("EMPTY_PROMPT")
        val process = runCatching {
            ProcessBuilder(listOf(config.executable.toString()) + args)
                .directory(config.workDir.toFile())
                .also(::configureEnvironment)
                .start()
        }.getOrElse {
            return CodexTurnResult.Failure("START_FAILED")
        }

        val answer = AtomicReference<String>()
        val failure = AtomicReference<String>()
        val turnCompleted = AtomicBoolean(false)
        val threadStarted = AtomicBoolean(false)
        val stderr = StringBuilder()
        val readers = Executors.newFixedThreadPool(2)
        readers.submit {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    parseEvent(
                        line = line,
                        answer = answer,
                        failure = failure,
                        turnCompleted = turnCompleted,
                        threadStarted = threadStarted,
                        onThreadStarted = onThreadStarted,
                    )
                }
            }
        }
        readers.submit {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (stderr.length < MAX_STDERR_CHARS) {
                        stderr.appendLine(line.take(MAX_STDERR_LINE_CHARS))
                    }
                }
            }
        }

        runCatching {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(prompt)
                writer.newLine()
            }
        }.onFailure {
            destroyProcessTree(process)
            readers.shutdownNow()
            return CodexTurnResult.Failure("STDIN_FAILED")
        }

        if (!process.waitFor(config.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            destroyProcessTree(process)
            readers.shutdownNow()
            return CodexTurnResult.Failure("TIMEOUT")
        }
        readers.shutdown()
        readers.awaitTermination(READER_JOIN_SECONDS, TimeUnit.SECONDS)

        if (stderr.isNotEmpty()) log.debug("Codex stderr category=PROCESS_OUTPUT")
        failure.get()?.let { return CodexTurnResult.Failure(it) }
        if (process.exitValue() != 0) return CodexTurnResult.Failure("EXIT_${process.exitValue()}")
        if (!turnCompleted.get()) return CodexTurnResult.Failure("INCOMPLETE_TURN")
        val finalAnswer = answer.get()?.takeIf { it.isNotBlank() }
            ?: return CodexTurnResult.Failure("MISSING_AGENT_MESSAGE")
        return CodexTurnResult.Success(finalAnswer)
    }

    private fun parseEvent(
        line: String,
        answer: AtomicReference<String>,
        failure: AtomicReference<String>,
        turnCompleted: AtomicBoolean,
        threadStarted: AtomicBoolean,
        onThreadStarted: (String) -> Unit,
    ) {
        if (failure.get() != null) return
        val event = runCatching {
            JsonSerializer.json.parseToJsonElement(line).jsonObject
        }.getOrElse {
            failure.compareAndSet(null, "INVALID_JSONL")
            return
        }
        when (event.string("type")) {
            "thread.started" -> {
                val threadId = event.string("thread_id")
                if (threadId == null || !THREAD_ID_PATTERN.matches(threadId)) {
                    failure.compareAndSet(null, "INVALID_THREAD_ID")
                } else if (!threadStarted.compareAndSet(false, true)) {
                    failure.compareAndSet(null, "DUPLICATE_THREAD_STARTED")
                } else {
                    runCatching { onThreadStarted(threadId) }
                        .onFailure { failure.compareAndSet(null, "THREAD_PERSIST_FAILED") }
                }
            }
            "item.completed" -> {
                val item = event["item"] as? JsonObject ?: return
                if (item.string("type") == "agent_message") {
                    item.string("text")?.let(answer::set)
                }
            }
            "turn.completed" -> turnCompleted.set(true)
            "turn.failed", "error" -> failure.compareAndSet(null, "TURN_FAILED")
        }
    }

    private fun configureEnvironment(builder: ProcessBuilder) {
        val inherited = builder.environment()
            .filterKeys { it in SAFE_INHERITED_ENVIRONMENT_KEYS }
        builder.environment().clear()
        builder.environment().putAll(inherited)
        builder.environment()["CODEX_HOME"] = config.codexHome.toString()
        builder.environment()["OPENAI_API_KEY"] = config.apiKey
    }

    private fun destroyProcessTree(process: Process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly)
        process.destroyForcibly()
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private companion object {
        val VERSION_PATTERN = Regex("""codex-cli\s+([0-9A-Za-z.+-]+)""")
        val THREAD_ID_PATTERN =
            Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
        val SAFE_INHERITED_ENVIRONMENT_KEYS = setOf(
            "PATH",
            "Path",
            "SystemRoot",
            "WINDIR",
            "TEMP",
            "TMP",
            "TMPDIR",
            "LANG",
            "LC_ALL",
            "SSL_CERT_FILE",
            "SSL_CERT_DIR",
        )
        const val VERSION_TIMEOUT_SECONDS = 5L
        const val READER_JOIN_SECONDS = 5L
        const val MAX_STDERR_CHARS = 8_000
        const val MAX_STDERR_LINE_CHARS = 500
    }
}
