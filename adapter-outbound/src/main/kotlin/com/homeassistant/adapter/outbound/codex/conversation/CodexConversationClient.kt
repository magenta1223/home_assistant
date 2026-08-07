package com.homeassistant.adapter.outbound.codex.conversation

import com.homeassistant.application.slackconversation.handle.ConversationTurnClient
import com.homeassistant.application.slackconversation.handle.ConversationTurnResult
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs Codex conversation turns and validates the configured CLI version. */
interface CodexConversationClient : ConversationTurnClient {
    /** Verifies that the configured Codex executable has the expected version. */
    fun validateVersion(): Boolean
}

internal class ProcessCodexConversationClient(
    private val config: CodexConversationConfig,
    private val eventParser: CodexJsonlEventParser = CodexJsonlEventParser(),
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
    ): ConversationTurnResult =
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

    override fun resume(threadId: String, prompt: String): ConversationTurnResult {
        if (!CODEX_THREAD_ID_PATTERN.matches(threadId)) {
            return ConversationTurnResult.Failure("INVALID_THREAD_ID")
        }
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
    ): ConversationTurnResult {
        if (prompt.isBlank()) return ConversationTurnResult.Failure("EMPTY_PROMPT")
        val process = runCatching {
            ProcessBuilder(listOf(config.executable.toString()) + args)
                .directory(config.workDir.toFile())
                .also(::configureEnvironment)
                .start()
        }.getOrElse {
            return ConversationTurnResult.Failure("START_FAILED")
        }

        val state = CodexEventState()
        val stderr = StringBuilder()
        val readers = Executors.newFixedThreadPool(2)
        readers.submit {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    eventParser.parse(line, state, onThreadStarted)
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
            return ConversationTurnResult.Failure("TIMEOUT")
        }
        readers.shutdown()
        readers.awaitTermination(READER_JOIN_SECONDS, TimeUnit.SECONDS)

        if (stderr.isNotEmpty()) log.debug("Codex stderr category=PROCESS_OUTPUT")
        state.failure.get()?.let { return ConversationTurnResult.Failure(it) }
        if (process.exitValue() != 0) return ConversationTurnResult.Failure("EXIT_${process.exitValue()}")
        if (!state.turnCompleted.get()) return ConversationTurnResult.Failure("INCOMPLETE_TURN")
        val finalAnswer = state.answer.get()?.takeIf { it.isNotBlank() }
            ?: return ConversationTurnResult.Failure("MISSING_AGENT_MESSAGE")
        return ConversationTurnResult.Success(finalAnswer)
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

    private companion object {
        val VERSION_PATTERN = Regex("""codex-cli\s+([0-9A-Za-z.+-]+)""")
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
