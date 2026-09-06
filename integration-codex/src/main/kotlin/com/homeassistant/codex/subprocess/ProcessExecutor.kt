package com.homeassistant.codex.subprocess

import java.nio.file.Path

/** Executes a Codex-related process and returns its outcome. */
fun interface ProcessExecutor {
    /** Executes a process with the supplied input and timeout. */
    fun execute(
        command: List<String>,
        workingDirectory: Path,
        timeoutMillis: Long,
        stdin: String,
    ): ProcessResult
}