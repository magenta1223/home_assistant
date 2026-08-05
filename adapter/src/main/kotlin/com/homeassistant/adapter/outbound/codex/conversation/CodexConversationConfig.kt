package com.homeassistant.adapter.outbound.codex.conversation

import com.homeassistant.core.constants.AppConfig
import com.homeassistant.core.constants.Env
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class CodexConversationConfig(
    val executable: Path,
    val expectedVersion: String,
    val workDir: Path,
    val codexHome: Path,
    val apiKey: String,
    val timeout: Duration,
) {
    companion object {
        fun fromEnv(
            readEnv: (String) -> String? = { Env[it] },
        ): CodexConversationConfig? {
            val executable = readEnv(AppConfig.ENV_VAR_CODEX_EXECUTABLE)
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
                ?: return null
            val expectedVersion = readEnv(AppConfig.ENV_VAR_CODEX_EXPECTED_VERSION)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val workDir = readEnv(AppConfig.ENV_VAR_CODEX_WORK_DIR)
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
                ?: return null
            val codexHome = readEnv(AppConfig.ENV_VAR_CODEX_HOME)
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
                ?: return null
            val apiKey = readEnv(AppConfig.ENV_VAR_CODEX_API_KEY)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val timeoutSeconds = readEnv(AppConfig.ENV_VAR_CODEX_TIMEOUT_SECONDS)
                ?.toLongOrNull()
                ?: AppConfig.DEFAULT_CODEX_TIMEOUT_SECONDS
            if (timeoutSeconds <= 0) return null
            if (!executable.isAbsolute || !workDir.isAbsolute || !codexHome.isAbsolute) return null

            val normalizedExecutable = executable.toAbsolutePath().normalize()
            val normalizedWorkDir = workDir.toAbsolutePath().normalize()
            val normalizedCodexHome = codexHome.toAbsolutePath().normalize()
            if (!Files.isRegularFile(normalizedExecutable)) return null
            if (!Files.isDirectory(normalizedWorkDir) || !Files.isDirectory(normalizedCodexHome)) return null
            if (Files.exists(normalizedWorkDir.resolve("db/homeAssistant.sqlite"))) return null

            return CodexConversationConfig(
                executable = normalizedExecutable,
                expectedVersion = expectedVersion,
                workDir = normalizedWorkDir,
                codexHome = normalizedCodexHome,
                apiKey = apiKey,
                timeout = Duration.ofSeconds(timeoutSeconds),
            )
        }
    }
}
