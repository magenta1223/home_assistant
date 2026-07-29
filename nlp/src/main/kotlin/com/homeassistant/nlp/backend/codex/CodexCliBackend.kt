package com.homeassistant.nlp.backend.codex

import com.homeassistant.core.nlp.LlmBackend
import com.homeassistant.core.nlp.LlmResponse
import com.homeassistant.core.nlp.Message
import com.homeassistant.core.tools.Tool
import com.homeassistant.nlp.backend.utils.parseToolCallOrText
import com.homeassistant.nlp.backend.utils.withTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal class CodexCliBackend(
    private val executable: String = defaultCodexExecutable(),
    private val timeoutMillis: Long = 180_000,
    private val processExecutor: CodexProcessExecutor = SystemCodexProcessExecutor,
) : LlmBackend {

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<Tool>,
        outputSchema: String,
    ): LlmResponse = withContext(Dispatchers.IO) {
        val workingDirectory = Files.createTempDirectory("homeassistant-codex-")
        try {
            val schemaFile = workingDirectory.resolve("output-schema.json")
            val outputFile = workingDirectory.resolve("output.json")
            schemaFile.writeText(outputSchema)
            val prompt = buildPrompt(system, messages, tools)

            val command = listOf(
                executable,
                "exec",
                "--ephemeral",
                "--sandbox", "read-only",
                "--skip-git-repo-check",
                "--output-schema", schemaFile.toString(),
                "--output-last-message", outputFile.toString(),
                "-",
            )
            val result = processExecutor.execute(command, workingDirectory, timeoutMillis, prompt)
            check(!result.timedOut) { "Codex CLI timed out after ${timeoutMillis}ms" }
            check(result.exitCode == 0) {
                "Codex CLI exited with code ${result.exitCode}: ${result.stderr.trim()}"
            }
            check(Files.exists(outputFile)) { "Codex CLI did not write its final response" }
            parseToolCallOrText(outputFile.readText())
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun buildPrompt(system: String, messages: List<Message>, tools: List<Tool>): String = buildString {
        appendLine(system.withTools(tools))
        messages.forEach { message ->
            appendLine()
            appendLine("[${message.role.value}]")
            append(message.content)
        }
    }
}

internal fun defaultCodexExecutable(osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Windows", ignoreCase = true)) "codex.cmd" else "codex"

internal data class CodexProcessResult(
    val exitCode: Int,
    val stderr: String,
    val timedOut: Boolean = false,
)

internal fun interface CodexProcessExecutor {
    fun execute(
        command: List<String>,
        workingDirectory: Path,
        timeoutMillis: Long,
        stdin: String,
    ): CodexProcessResult
}

private object SystemCodexProcessExecutor : CodexProcessExecutor {
    override fun execute(
        command: List<String>,
        workingDirectory: Path,
        timeoutMillis: Long,
        stdin: String,
    ): CodexProcessResult {
        val stdoutFile = workingDirectory.resolve("stdout.log").toFile()
        val stderrFile = workingDirectory.resolve("stderr.log").toFile()
        val process = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
        val completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) process.destroyForcibly().waitFor()
        return CodexProcessResult(
            exitCode = if (completed) process.exitValue() else -1,
            stderr = stderrFile.readText(),
            timedOut = !completed,
        )
    }
}

private fun Path.deleteRecursively() {
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
