package com.homeassistant.adapter.outbound.codex.conversation

import com.homeassistant.adapter.outbound.codex.defaultCodexExecutable
import com.homeassistant.configuration.AppConfig
import com.homeassistant.configuration.Env
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class CodexConversationConfig(
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
            readEnv: (String) -> String? = { Env[it] },
            executable: String = defaultCodexExecutable(),
            temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        ): CodexConversationConfig? {
            val timeoutSeconds = readEnv(AppConfig.ENV_VAR_CODEX_TIMEOUT_SECONDS)
                ?.toLongOrNull()
                ?: AppConfig.DEFAULT_CODEX_TIMEOUT_SECONDS
            if (timeoutSeconds <= 0) return null
            val normalizedWorkDir = temporaryDirectory.toAbsolutePath()
                .normalize()
                .resolve("homeassistant-codex-conversation")
            if (runCatching { Files.createDirectories(normalizedWorkDir) }.isFailure) return null

            return CodexConversationConfig(
                executable = executable,
                workDir = normalizedWorkDir,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        }
    }
}
