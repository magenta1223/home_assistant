package com.homeassistant.llm.claude.cli

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class ClaudeProcessOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/** Runs one Claude Code CLI process to completion. */
internal fun interface ClaudeProcessRunner {
    fun run(command: List<String>, workingDirectory: Path, stdin: String, timeout: Duration): ClaudeProcessOutput
}

internal object SystemClaudeProcessRunner : ClaudeProcessRunner {
    override fun run(
        command: List<String>,
        workingDirectory: Path,
        stdin: String,
        timeout: Duration,
    ): ClaudeProcessOutput {
        val process = ProcessBuilder(ClaudeCommandLine.platformArguments(command))
            .directory(workingDirectory.toFile())
            .start()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroy(process)
                return ClaudeProcessOutput(exitCode = -1, stdout = "", stderr = "", timedOut = true)
            }
            return ClaudeProcessOutput(process.exitValue(), stdout.join(), stderr.join())
        } catch (error: InterruptedException) {
            destroy(process)
            throw error
        }
    }

    private fun destroy(process: Process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly)
        process.destroyForcibly().waitFor()
    }
}
