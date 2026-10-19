package com.homeassistant.llm.claude.conversation

import com.homeassistant.llm.claude.cli.ClaudeAvailability
import com.homeassistant.llm.claude.cli.ClaudeCommandLine
import com.homeassistant.llm.conversation.ConversationClient
import java.nio.file.Path
import java.time.Duration

internal object ClaudeConversationClientFactory {
    /** Returns null when the timeout is invalid or the Claude Code CLI cannot be started. */
    fun create(
        timeout: Duration,
        executable: String = ClaudeCommandLine.executable(),
        temporaryDirectory: Path = Path.of(System.getProperty("java.io.tmpdir")),
        model: String = ClaudeConversationConfig.DEFAULT_MODEL,
        effort: String = ClaudeConversationConfig.DEFAULT_EFFORT,
    ): ConversationClient? {
        val config = ClaudeConversationConfig.local(timeout, executable, temporaryDirectory, model, effort)
            ?: return null
        if (!ClaudeAvailability.probe(executable)) return null
        return ClaudeConversationClient(config)
    }
}
