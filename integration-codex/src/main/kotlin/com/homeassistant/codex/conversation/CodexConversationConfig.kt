package com.homeassistant.codex.conversation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal data class CodexConversationConfig(
    val executable: String,
    val workDir: Path,
    val timeout: Duration,
    val model: String = DEFAULT_MODEL,
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        const val DEFAULT_REASONING_EFFORT = "medium"

        fun local(
            timeout: Duration,
            executable: String,
            temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
            model: String = DEFAULT_MODEL,
            reasoningEffort: String = DEFAULT_REASONING_EFFORT,
        ): CodexConversationConfig? {
            if (timeout.isZero || timeout.isNegative) return null
            val normalizedWorkDir = temporaryDirectory.toAbsolutePath()
                .normalize()
                .resolve("homeassistant-codex-conversation")
            if (runCatching { Files.createDirectories(normalizedWorkDir) }.isFailure) return null

            return CodexConversationConfig(
                executable = executable,
                workDir = normalizedWorkDir,
                timeout = timeout,
                model = model,
                reasoningEffort = reasoningEffort,
            )
        }
    }
}
