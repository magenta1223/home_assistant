package com.homeassistant.codex.subprocess

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.use

object SystemProcessExecutor : ProcessExecutor {
    override fun execute(
        command: List<String>,
        workingDirectory: Path,
        timeoutMillis: Long,
        stdin: String,
    ): ProcessResult {
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
        return ProcessResult(
            exitCode = if (completed) process.exitValue() else -1,
            stderr = stderrFile.readText(),
            timedOut = !completed,
        )
    }
}