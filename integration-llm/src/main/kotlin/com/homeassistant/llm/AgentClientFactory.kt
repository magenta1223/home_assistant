package com.homeassistant.llm

import com.homeassistant.llm.claude.completion.ClaudeCliCompletionClient
import com.homeassistant.llm.claude.chat.ClaudeConversationClientFactory
import com.homeassistant.llm.codex.completion.CodexCliClient
import com.homeassistant.llm.codex.conversation.CodexAppServerFactory
import java.time.Duration

/** Routes completion and conversation clients to the selected [AgentBackend]. */
object AgentClientFactory {
    fun completion(backend: AgentBackend): CompletionClient = when (backend) {
        AgentBackend.CODEX -> CodexCliClient()
        AgentBackend.CLAUDE -> ClaudeCliCompletionClient()
    }

    /** Returns null when the backend's local CLI is unavailable or [timeout] is invalid. */
    fun conversation(backend: AgentBackend, timeout: Duration): ChatClient? = when (backend) {
        AgentBackend.CODEX -> CodexAppServerFactory.create(timeout)
        AgentBackend.CLAUDE -> ClaudeConversationClientFactory.create(timeout)
    }
}
