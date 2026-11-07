package com.homeassistant.llm

import com.homeassistant.llm.claude.completion.ClaudeCliCompletionClient
import com.homeassistant.llm.claude.chat.ClaudeChatClientFactory
import com.homeassistant.llm.codex.completion.CodexCompletionClient
import com.homeassistant.llm.codex.conversation.CodexChatClientFactory
import java.time.Duration

/** Routes completion and conversation clients to the selected [AgentBackend]. */
object AgentClientFactory {
    fun completion(backend: AgentBackend): CompletionClient = when (backend) {
        AgentBackend.CODEX -> CodexCompletionClient()
        AgentBackend.CLAUDE -> ClaudeCliCompletionClient()
    }

    /** Returns null when the backend's local CLI is unavailable or [timeout] is invalid. */
    fun conversation(backend: AgentBackend, timeout: Duration): ChatClient? = when (backend) {
        AgentBackend.CODEX -> CodexChatClientFactory.create(timeout)
        AgentBackend.CLAUDE -> ClaudeChatClientFactory.create(timeout)
    }
}
