package com.homeassistant.llm.claude.conversation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal data class ClaudeConversationConfig(
    val executable: String,
    /** Claude Code stores sessions per working directory, so every turn must run in this one directory. */
    val workDir: Path,
    val timeout: Duration,
    val model: String = DEFAULT_MODEL,
    val effort: String = DEFAULT_EFFORT,
) {
    val systemPromptFile: Path get() = workDir.resolve(SYSTEM_PROMPT_FILE)

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_EFFORT = "medium"
        private const val SYSTEM_PROMPT_FILE = "system-prompt.txt"
        private const val SYSTEM_PROMPT =
            "You answer a household member's questions in a private second-brain assistant. " +
                "Follow the instructions and use only the reference material given in each message."

        fun local(
            timeout: Duration,
            executable: String,
            temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
            model: String = DEFAULT_MODEL,
            effort: String = DEFAULT_EFFORT,
        ): ClaudeConversationConfig? {
            if (timeout.isZero || timeout.isNegative) return null
            val workDir = temporaryDirectory.toAbsolutePath()
                .normalize()
                .resolve("homeassistant-claude-conversation")
            val config = ClaudeConversationConfig(executable, workDir, timeout, model, effort)
            return config.takeIf {
                runCatching {
                    Files.createDirectories(workDir)
                    Files.writeString(config.systemPromptFile, SYSTEM_PROMPT)
                }.isSuccess
            }
        }
    }
}
