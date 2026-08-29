package com.homeassistant.adapter.outbound.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Completes a single structured Codex prompt. */
internal fun interface CodexCompletionClient {
    /** Runs one completion request and returns the structured response text. */
    suspend fun complete(system: String, userMessage: String, outputSchema: String): String

    suspend fun completeWithImages(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CodexImage>,
    ): String = if (images.isEmpty()) {
        complete(system, userMessage, outputSchema)
    } else {
        error("image completion is unavailable")
    }
}

internal data class CodexImage(val fileName: String, val bytes: ByteArray)

internal class CodexCliClient(
    private val executable: String = defaultCodexExecutable(),
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val processExecutor: CodexProcessExecutor = SystemCodexProcessExecutor,
) : CodexCompletionClient {
    override suspend fun complete(
        system: String,
        userMessage: String,
        outputSchema: String,
    ): String = completeWithImages(system, userMessage, outputSchema, emptyList())

    override suspend fun completeWithImages(
        system: String,
        userMessage: String,
        outputSchema: String,
        images: List<CodexImage>,
    ): String = withContext(Dispatchers.IO) {
        val workingDirectory = Files.createTempDirectory("homeassistant-codex-")
        try {
            val schemaFile = workingDirectory.resolve("output-schema.json")
            val outputFile = workingDirectory.resolve("output.json")
            schemaFile.writeText(outputSchema)
            val command = mutableListOf(
                executable,
                "exec",
                "--model", MEMORY_GENERATION_MODEL,
                "--ephemeral",
                "--ignore-user-config",
                "--ignore-rules",
                "--config", "model_reasoning_effort=\"$MEMORY_GENERATION_REASONING_EFFORT\"",
                "--sandbox", "read-only",
                "--skip-git-repo-check",
                "--output-schema", schemaFile.toString(),
                "--output-last-message", outputFile.toString(),
            )
            images.forEachIndexed { index, image ->
                val extension = image.fileName.substringAfterLast('.', "png")
                    .lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                    ?: "png"
                val imageFile = workingDirectory.resolve("input-${index + 1}.$extension")
                imageFile.writeBytes(image.bytes)
                command += listOf("--image", imageFile.toString())
            }
            command += "-"
            val result = processExecutor.execute(
                command,
                workingDirectory,
                timeoutMillis,
                buildPrompt(system, userMessage),
            )
            check(!result.timedOut) { "Codex CLI timed out after ${timeoutMillis}ms" }
            check(result.exitCode == 0) {
                "Codex CLI exited with code ${result.exitCode}: ${result.stderr.trim()}"
            }
            check(Files.exists(outputFile)) { "Codex CLI did not write its final response" }
            outputFile.readText()
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun buildPrompt(system: String, userMessage: String): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("[user]")
        append(userMessage)
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10 * 60 * 1_000L
        const val MEMORY_GENERATION_MODEL = "gpt-5.6-sol"
        const val MEMORY_GENERATION_REASONING_EFFORT = "high"
    }
}

internal fun defaultCodexExecutable(osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Windows", ignoreCase = true)) "codex.cmd" else "codex"

internal data class CodexProcessResult(
    val exitCode: Int,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** Executes a Codex-related process and returns its outcome. */
internal fun interface CodexProcessExecutor {
    /** Executes a process with the supplied input and timeout. */
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
