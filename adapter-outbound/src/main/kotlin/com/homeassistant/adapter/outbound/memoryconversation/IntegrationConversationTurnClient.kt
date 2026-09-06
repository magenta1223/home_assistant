package com.homeassistant.adapter.outbound.memoryconversation

import com.homeassistant.application.port.output.memory.conversation.ConversationTurnResult
import com.homeassistant.codex.conversation.ConversationClient

internal class IntegrationConversationTurnClient(
    private val client: ConversationClient,
) : ManagedConversationTurnClient {
    override fun isAvailable(): Boolean = client.isAvailable()

    override fun startServer(): Boolean = client.startServer()

    override fun start(
        prompt: String,
        onThreadStarted: (String) -> Unit,
    ): ConversationTurnResult = client.start(prompt, onThreadStarted).toTurnResult()

    override fun resume(threadId: String, prompt: String): ConversationTurnResult =
        client.resume(threadId, prompt).toTurnResult()

    override fun end(threadId: String) {
        client.end(threadId)
    }

    override fun close() {
        client.close()
    }

    private fun Result<String>.toTurnResult(): ConversationTurnResult = fold(
        onSuccess = ConversationTurnResult::Success,
        onFailure = { ConversationTurnResult.Failure(it.message ?: "CODEX_CONVERSATION_FAILURE") },
    )
}
