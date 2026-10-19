package com.homeassistant.llm.claude.cli

import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/** Reports whether the Claude Code CLI can be started. It does not check the login. */
internal object ClaudeAvailability {
    fun probe(executable: String): Boolean {
        val process = runCatching {
            ProcessBuilder(executable, "--version").redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return false
        }
        val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
        return process.exitValue() == 0 && output.contains("(Claude Code)")
    }
}
