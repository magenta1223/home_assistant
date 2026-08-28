package com.homeassistant.adapter.outbound.codex.conversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnClient
import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs Codex conversation turns and validates that the local CLI is available. */
interface CodexConversationClient : ConversationTurnClient, AutoCloseable {
    /** Verifies that the configured Codex executable can be launched. */
    fun isAvailable(): Boolean

    /** Starts and initializes the long-lived Codex runtime. */
    fun startServer(): Boolean
}

internal class ProcessCodexConversationClient(
    private val config: CodexConversationConfig,
    private val eventParser: CodexJsonlEventParser = CodexJsonlEventParser(),
) : CodexConversationClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isAvailable(): Boolean {
        val process = runCatching {
            ProcessBuilder(config.executable, "--version")
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
        return process.exitValue() == 0 && version != null
    }

    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): ConversationTurnResult =
        execute(
            operation = "start",
            args = listOf(
                "exec",
                "--json",
                "--model",
                config.model,
                "--sandbox",
                "read-only",
                "--skip-git-repo-check",
                "--ignore-user-config",
                "--ignore-rules",
                "-c",
                "approval_policy=\"never\"",
                "-c",
                "web_search=\"disabled\"",
                "-c",
                "model_reasoning_effort=\"${config.reasoningEffort}\"",
                "-C",
                config.workDir.toString(),
                "-",
            ),
            prompt = prompt,
            onThreadStarted = onThreadStarted,
        )

    override fun resume(threadId: String, prompt: String): ConversationTurnResult {
        if (!CODEX_THREAD_ID_PATTERN.matches(threadId)) {
            return ConversationTurnResult.Failure("INVALID_THREAD_ID")
        }
        return execute(
            operation = "resume",
            args = listOf(
                "exec",
                "resume",
                "--json",
                "--model",
                config.model,
                "--skip-git-repo-check",
                "--ignore-user-config",
                "--ignore-rules",
                "-c",
                "approval_policy=\"never\"",
                "-c",
                "sandbox_mode=\"read-only\"",
                "-c",
                "web_search=\"disabled\"",
                "-c",
                "model_reasoning_effort=\"${config.reasoningEffort}\"",
                threadId,
                "-",
            ),
            prompt = prompt,
            onThreadStarted = {},
        )
    }

    private fun execute(
        operation: String,
        args: List<String>,
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): ConversationTurnResult {
        if (prompt.isBlank()) return ConversationTurnResult.Failure("EMPTY_PROMPT")
        val executionStartedAt = System.nanoTime()
        val process = runCatching {
            ProcessBuilder(listOf(config.executable) + args)
                .directory(config.workDir.toFile())
                .start()
        }.getOrElse {
            log.warn(
                "Latency stage=codex-process-start operation={} result=failure model={} category={} elapsedMs={}",
                operation,
                config.model,
                it.javaClass.simpleName,
                elapsedMillis(executionStartedAt),
            )
            return ConversationTurnResult.Failure("START_FAILED")
        }
        log.info(
            "Latency stage=codex-process-start operation={} result=success model={} reasoningEffort={} elapsedMs={}",
            operation,
            config.model,
            config.reasoningEffort,
            elapsedMillis(executionStartedAt),
        )

        val state = CodexEventState()
        val stderr = StringBuilder()
        val readers = Executors.newFixedThreadPool(2)
        readers.submit {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    eventParser.parse(line, state) { threadId ->
                        log.info(
                            "Latency stage=codex-thread-ready operation={} model={} elapsedMs={}",
                            operation,
                            config.model,
                            elapsedMillis(executionStartedAt),
                        )
                        onThreadStarted(threadId)
                    }
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
            return ConversationTurnResult.Failure("STDIN_FAILED")
        }

        if (!process.waitFor(config.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            destroyProcessTree(process)
            readers.shutdownNow()
            log.warn(
                "Latency stage=codex-turn operation={} result=timeout model={} elapsedMs={}",
                operation,
                config.model,
                elapsedMillis(executionStartedAt),
            )
            return ConversationTurnResult.Failure("TIMEOUT")
        }
        readers.shutdown()
        readers.awaitTermination(READER_JOIN_SECONDS, TimeUnit.SECONDS)
        log.info(
            "Latency stage=codex-turn operation={} result=completed model={} exitCode={} threadReady={} answerReady={} elapsedMs={}",
            operation,
            config.model,
            process.exitValue(),
            state.threadStarted.get(),
            state.answer.get() != null,
            elapsedMillis(executionStartedAt),
        )

        if (stderr.isNotEmpty()) log.debug("Codex stderr category=PROCESS_OUTPUT")
        state.failure.get()?.let { return ConversationTurnResult.Failure(it) }
        if (process.exitValue() != 0) return ConversationTurnResult.Failure("EXIT_${process.exitValue()}")
        if (!state.turnCompleted.get()) return ConversationTurnResult.Failure("INCOMPLETE_TURN")
        val finalAnswer = state.answer.get()?.takeIf { it.isNotBlank() }
            ?: return ConversationTurnResult.Failure("MISSING_AGENT_MESSAGE")
        return ConversationTurnResult.Success(finalAnswer)
    }

    private fun destroyProcessTree(process: Process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly)
        process.destroyForcibly()
    }

    override fun startServer(): Boolean = true

    override fun end(threadId: String) = Unit

    override fun close() = Unit

    private fun elapsedMillis(startedAt: Long): Long =
        Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    private companion object {
        val VERSION_PATTERN = Regex("""codex-cli\s+([0-9A-Za-z.+-]+)""")
        const val VERSION_TIMEOUT_SECONDS = 30L
        const val READER_JOIN_SECONDS = 5L
        const val MAX_STDERR_CHARS = 8_000
        const val MAX_STDERR_LINE_CHARS = 500
    }
}
