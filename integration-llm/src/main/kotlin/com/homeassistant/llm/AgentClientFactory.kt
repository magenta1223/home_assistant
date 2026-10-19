package com.homeassistant.llm

import com.homeassistant.application.port.output.memory.conversation.ConversationGateway
import com.homeassistant.llm.claude.completion.ClaudeCompletionClientFactory
import com.homeassistant.llm.claude.conversation.ClaudeConversationGatewayFactory
import com.homeassistant.llm.codex.completion.CodexCompletionClientFactory
import com.homeassistant.llm.codex.conversation.CodexAppServerFactory
import com.homeassistant.llm.codex.conversation.CodexConversationGateway
import com.homeassistant.llm.completion.CompletionClient
import java.time.Duration

/** Routes completion and conversation clients to the selected [AgentBackend]. */
object AgentClientFactory {
    fun completion(backend: AgentBackend): CompletionClient = when (backend) {
        AgentBackend.CODEX -> CodexCompletionClientFactory.create()
        AgentBackend.CLAUDE -> ClaudeCompletionClientFactory.create()
    }

    /** Returns null when the backend's local CLI is unavailable or [timeout] is invalid. */
    fun conversation(backend: AgentBackend, timeout: Duration): ConversationGateway? = when (backend) {
        AgentBackend.CODEX -> CodexAppServerFactory.create(timeout)?.let(::CodexConversationGateway)
        AgentBackend.CLAUDE -> ClaudeConversationGatewayFactory.create(timeout)
    }
}
